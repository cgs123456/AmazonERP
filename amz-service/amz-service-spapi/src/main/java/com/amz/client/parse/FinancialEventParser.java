package com.amz.client.parse;

import com.amz.client.dto.FinancialEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SP-API Finances v0 原始事件 → 业务口径 {@link FinancialEvent} 的解析器。
 * <p>
 * 处理三类原始事件结构：
 * <ul>
 *   <li>ShipmentEventList —— ItemChargeList 中的 Principal 记为 INCOME，
 *       ItemFeeList（Commission / FBAPerUnitFulfillmentFee 等）记为 FEE（取负）</li>
 *   <li>RefundEventList —— Principal 记为 REFUND（取负）</li>
 *   <li>AdjustmentEventList —— 记为 ADJUSTMENT（保留原始符号，FBA 库存赔付为正）</li>
 * </ul>
 * <p>
 * eventId 为确定性幂等键（type:orderId:postedAt:feeType:index）——
 * 同一窗口重复拉取时上层按 eventId 去重即可保证幂等。
 * <p>
 * 独立成静态解析器而非埋在客户端里：真实联调无凭证时，
 * 这是「字段路径、符号约定、幂等键」唯一能被单测完全验证的层。
 */
public final class FinancialEventParser {

    private FinancialEventParser() {
    }

    /**
     * 解析 FinancialEvents 节点（即 SP-API 响应中 payload.FinancialEvents 的值）。
     * 入参为 null / 空对象时返回空列表（SP-API 无事件的窗口就是这么返回的，不是异常）。
     */
    public static List<FinancialEvent> parse(JsonObject financialEvents) {
        List<FinancialEvent> out = new ArrayList<>();
        if (financialEvents == null) {
            return out;
        }
        parseShipmentEvents(array(financialEvents, "ShipmentEventList"), out, false);
        parseShipmentEvents(array(financialEvents, "RefundEventList"), out, true);
        parseAdjustmentEvents(array(financialEvents, "AdjustmentEventList"), out);
        return out;
    }

    /**
     * ShipmentEvent 与 RefundEvent 结构同构（都有 ShipmentItemList），
     * 差别仅在符号与类型：isRefund=true 时 Principal 记为 REFUND 且取负。
     */
    private static void parseShipmentEvents(JsonArray events, List<FinancialEvent> out, boolean isRefund) {
        if (events == null) {
            return;
        }
        for (JsonElement el : events) {
            JsonObject ev = el.getAsJsonObject();
            String orderId = str(ev, "AmazonOrderId");
            String postedAt = str(ev, "PostedDate");
            JsonArray items = array(ev, "ShipmentItemList");
            if (items == null) {
                continue;
            }
            for (JsonElement itemEl : items) {
                JsonObject item = itemEl.getAsJsonObject();
                String sku = str(item, "SellerSKU");
                String asin = str(item, "ASIN");
                String orderItemId = str(item, "OrderItemId");

                // Principal 收入（发货 = INCOME，退款 = REFUND）
                String incomeType = isRefund ? FinancialEvent.TYPE_REFUND : FinancialEvent.TYPE_INCOME;
                JsonArray charges = array(item, "ItemChargeList");
                if (charges != null) {
                    for (JsonElement c : charges) {
                        JsonObject charge = c.getAsJsonObject();
                        if ("Principal".equals(str(charge, "ChargeType"))) {
                            Money m = money(charge);
                            if (m != null) {
                                FinancialEvent fe = base(incomeType, orderId, postedAt, "Principal", m.amount);
                                fe.setSku(sku);
                                fe.setAsin(asin);
                                fe.setDescription("Principal " + orderItemId);
                                fe.setAmount(isRefund ? m.amount.negate() : m.amount);
                                fe.setCurrency(m.currency);
                                out.add(fe);
                            }
                        }
                    }
                }
                // 平台扣费（ItemFeeList，发货与退款场景都可能出现，均记为 FEE 取负）
                JsonArray fees = array(item, "ItemFeeList");
                if (fees != null) {
                    for (JsonElement f : fees) {
                        JsonObject fee = f.getAsJsonObject();
                        Money m = money(fee);
                        if (m != null) {
                            String feeType = str(fee, "ChargeType");
                            // 保留原始符号：发货场景 SP-API 费用为负；退款场景费用返还为正。
                            // 不强行取负 —— 费用返还是卖家的正向现金流，强行翻号会把「退钱」记成「扣钱」。
                            FinancialEvent fe = base(FinancialEvent.TYPE_FEE, orderId, postedAt,
                                    feeType, m.amount);
                            fe.setSku(sku);
                            fe.setAsin(asin);
                            fe.setAmount(m.amount);
                            fe.setCurrency(m.currency);
                            fe.setDescription(feeType);
                            out.add(fe);
                        }
                    }
                }
            }
        }
    }

    /**
     * AdjustmentEvent：FBA 库存赔付（正向）、争议裁决（可正可负）等。
     * 保留 SP-API 原始符号 —— 这是索赔闭环（T06/T08）识别「平台已赔付金额」的关键来源。
     */
    private static void parseAdjustmentEvents(JsonArray events, List<FinancialEvent> out) {
        if (events == null) {
            return;
        }
        for (JsonElement el : events) {
            JsonObject ev = el.getAsJsonObject();
            String orderId = str(ev, "AmazonOrderId");
            String postedAt = str(ev, "PostedDate");
            String adjustmentType = str(ev, "AdjustmentType");
            Money m = money(ev, "AdjustmentAmount");
            if (m == null) {
                continue;
            }
            FinancialEvent fe = base(FinancialEvent.TYPE_ADJUSTMENT, orderId, postedAt,
                    adjustmentType, m.amount);
            fe.setAmount(m.amount);
            fe.setCurrency(m.currency);
            fe.setDescription(adjustmentType);
            out.add(fe);
        }
    }

    /** 构造事件骨架并生成确定性幂等键。 */
    private static FinancialEvent base(String type, String orderId, String postedAt,
                                       String feeType, BigDecimal amount) {
        FinancialEvent fe = new FinancialEvent();
        fe.setType(type);
        fe.setAmazonOrderId(orderId);
        fe.setPostedAt(postedAt);
        fe.setFeeType(feeType);
        fe.setEventId(eventKey(type, orderId, postedAt, feeType, amount));
        return fe;
    }

    /** 确定性幂等键：同一事件重复拉取生成相同 key，上层据此去重。 */
    static String eventKey(String type, String orderId, String postedAt, String feeType,
                           BigDecimal amount) {
        return type + ":" + nz(orderId) + ":" + nz(postedAt) + ":" + nz(feeType) + ":"
                + (amount == null ? "" : amount.stripTrailingZeros().toPlainString());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement e = o.get(key);
        return e.isJsonObject() ? null : e.getAsString();
    }

    private static JsonArray array(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement e = o.get(key);
        return e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /**
     * 解析 ChargeAmount / AdjustmentAmount 结构 {CurrencyCode, Amount}。
     * Amount 缺失或非法时返回 null（跳过该条，不让单条脏数据中断整批）。
     */
    private static Money money(JsonObject holder) {
        if (holder == null) {
            return null;
        }
        return money(holder, "ChargeAmount");
    }

    private static Money money(JsonObject holder, String key) {
        JsonObject amountObj = holder.has(key) && holder.get(key).isJsonObject()
                ? holder.getAsJsonObject(key) : null;
        if (amountObj == null) {
            return null;
        }
        String currency = str(amountObj, "CurrencyCode");
        String amountStr = str(amountObj, "Amount");
        if (amountStr == null) {
            return null;
        }
        try {
            return new Money(new BigDecimal(amountStr), currency);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Money {
        final BigDecimal amount;
        final String currency;

        Money(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }
    }
}
