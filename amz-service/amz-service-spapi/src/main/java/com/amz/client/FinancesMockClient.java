package com.amz.client;

import com.amz.client.dto.FinancialEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SP-API Finances 模拟客户端。
 * <p>
 * 仅在 {@code spring.profiles.active=mock} 时生效。
 * 返回确定性事件集：正常销售（收入 + 两笔费用）、含退款订单、一笔 FBA 库存赔付 ——
 * 覆盖四类事件与回款 / 索赔两条下游链路需要的全部形态。
 * <p>
 * 时间窗口过滤用 ISO-8601 字符串字典序比较（事件时间固定 UTC 同格式，字典序 = 时间序）。
 */
@Component
@Profile("mock")
public class FinancesMockClient implements FinancesClient {

    private static final Logger log = LoggerFactory.getLogger(FinancesMockClient.class);

    private static final List<FinancialEvent> EVENTS = buildEvents();

    @Override
    public List<FinancialEvent> listFinancialEvents(Long shopId, String postedAfter, String postedBefore) {
        List<FinancialEvent> filtered = new ArrayList<>();
        for (FinancialEvent e : EVENTS) {
            if (postedAfter != null && !postedAfter.isBlank()
                    && e.getPostedAt().compareTo(postedAfter) < 0) {
                continue;
            }
            if (postedBefore != null && !postedBefore.isBlank()
                    && e.getPostedAt().compareTo(postedBefore) > 0) {
                continue;
            }
            filtered.add(e);
        }
        log.info("listFinancialEvents (mock) shopId={} postedAfter={} postedBefore={} events={}",
                shopId, postedAfter, postedBefore, filtered.size());
        return filtered;
    }

    private static List<FinancialEvent> buildEvents() {
        List<FinancialEvent> list = new ArrayList<>();

        // 订单一：正常销售（收入 + 佣金 + FBA 配送费）
        list.add(event("INCOME:111-0000001-0000001:2026-09-02T12:00:00Z:Principal:29.99",
                FinancialEvent.TYPE_INCOME, "111-0000001-0000001", "SKU-ALPHA", "B0ALPHA",
                "2026-09-02T12:00:00Z", "29.99", "Principal"));
        list.add(event("FEE:111-0000001-0000001:2026-09-02T12:00:00Z:Commission:4.5",
                FinancialEvent.TYPE_FEE, "111-0000001-0000001", "SKU-ALPHA", "B0ALPHA",
                "2026-09-02T12:00:00Z", "-4.50", "Commission"));
        list.add(event("FEE:111-0000001-0000001:2026-09-02T12:00:00Z:FBAFulfillmentFee:5.03",
                FinancialEvent.TYPE_FEE, "111-0000001-0000001", "SKU-ALPHA", "B0ALPHA",
                "2026-09-02T12:00:00Z", "-5.03", "FBAFulfillmentFee"));

        // 订单二：销售后全额退款
        list.add(event("INCOME:111-0000002-0000002:2026-09-03T12:00:00Z:Principal:49.99",
                FinancialEvent.TYPE_INCOME, "111-0000002-0000002", "SKU-BETA", "B0BETA",
                "2026-09-03T12:00:00Z", "49.99", "Principal"));
        list.add(event("FEE:111-0000002-0000002:2026-09-03T12:00:00Z:Commission:7.5",
                FinancialEvent.TYPE_FEE, "111-0000002-0000002", "SKU-BETA", "B0BETA",
                "2026-09-03T12:00:00Z", "-7.50", "Commission"));
        list.add(event("REFUND:111-0000002-0000002:2026-09-05T12:00:00Z:Principal:49.99",
                FinancialEvent.TYPE_REFUND, "111-0000002-0000002", "SKU-BETA", "B0BETA",
                "2026-09-05T12:00:00Z", "-49.99", "Principal"));

        // FBA 库存赔付（正向调整）—— 索赔闭环的关键形态
        list.add(event("ADJUSTMENT::2026-09-06T12:00:00Z:FBA Inventory Reimbursement:12.5",
                FinancialEvent.TYPE_ADJUSTMENT, null, "SKU-GAMMA", "B0GAMMA",
                "2026-09-06T12:00:00Z", "12.50", "FBA Inventory Reimbursement"));

        return List.copyOf(list);
    }

    private static FinancialEvent event(String eventId, String type, String orderId, String sku,
                                        String asin, String postedAt, String amount, String feeType) {
        FinancialEvent e = new FinancialEvent();
        e.setEventId(eventId);
        e.setType(type);
        e.setAmazonOrderId(orderId);
        e.setSku(sku);
        e.setAsin(asin);
        e.setPostedAt(postedAt);
        e.setAmount(new BigDecimal(amount));
        e.setCurrency("USD");
        e.setFeeType(feeType);
        e.setDescription(feeType);
        return e;
    }
}
