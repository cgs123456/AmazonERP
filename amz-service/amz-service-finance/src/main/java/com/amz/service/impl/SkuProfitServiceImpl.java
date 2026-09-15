package com.amz.service.impl;

import com.amz.client.ProcurementCostClient;
import com.amz.client.dto.RemoteInventoryBatch;
import com.amz.dto.SkuProfitReport;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.SettlementDetail;
import com.amz.result.Result;
import com.amz.service.SkuProfitService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单品真实利润实现（T09）。
 * <p>
 * 三个数据来源，各有各的坑：
 * <ul>
 *   <li><b>收入与费用</b>：结算原表（平台实际扣费）。这一条是「真实」二字的来源 ——
 *       用商品定价与费率表估算出来的利润，只能叫预测</li>
 *   <li><b>赔付追回</b>：结算原表的 Adjustment 行（钱到账了才算）</li>
 *   <li><b>成本</b>：采购批次。批次里已含头程运费与关税，因此<b>不再叠加物流头程分摊</b>，
 *       否则同一笔运费计两次</li>
 * </ul>
 * 成本拿不到时：<b>profit 不含成本、并置 costMissing / profitIsComplete=false</b>。
 * 绝不按 0 计 —— 按 0 会得出「利润率很高」的错误结论，且没人会质疑一个好看的数。
 */
@Slf4j
@Service
public class SkuProfitServiceImpl implements SkuProfitService {

    /** 单次报告最多查询成本的 SKU 数（避免一次报表打出上千次 Feign 调用）。 */
    @Value("${amz.finance.profit.max-cost-query-skus:100}")
    private int maxCostQuerySkus = 100;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private ProcurementCostClient procurementCostClient;

    @Override
    public SkuProfitReport compute(Long shopId, String depositAfter, String depositBefore, String sku) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        SkuProfitReport report = new SkuProfitReport();
        report.setShopId(shopId);
        report.setDepositAfter(depositAfter);
        report.setDepositBefore(depositBefore);

        List<SettlementDetail> rows = settlementDetailMapper.selectList(
                new LambdaQueryWrapper<SettlementDetail>().eq(SettlementDetail::getShopId, shopId));
        if (rows == null || rows.isEmpty()) {
            report.addWarning("暂无结算明细，无法计算真实利润（请先同步结算原表）");
            report.setCostDataComplete(false);
            return report;
        }

        Map<String, List<SettlementDetail>> bySku = new LinkedHashMap<>();
        int unattributed = 0;
        for (SettlementDetail row : rows) {
            if (!inWindow(row.getDepositDate(), depositAfter, depositBefore)) {
                continue;
            }
            if (row.getSku() == null || row.getSku().isBlank()) {
                unattributed++;
                continue;
            }
            if (sku != null && !sku.isBlank() && !sku.equals(row.getSku())) {
                continue;
            }
            bySku.computeIfAbsent(row.getSku(), k -> new ArrayList<>()).add(row);
        }
        if (bySku.isEmpty()) {
            report.addWarning("指定条件下没有可计算的 SKU（窗口内无结算明细或该 SKU 无记录）");
            report.setCostDataComplete(false);
            if (unattributed > 0) {
                report.addWarning("有 " + unattributed + " 条结算行无 SKU，未参与计算");
            }
            return report;
        }

        report.setSkuCount(bySku.size());
        boolean truncated = bySku.size() > maxCostQuerySkus;
        int costQueried = 0;
        int costMissingCount = 0;
        List<String> costMissingSkus = new ArrayList<>();
        SkuProfitReport.SkuProfit totals = new SkuProfitReport.SkuProfit();
        totals.setSku("TOTAL");
        // boolean 默认 false，合计行必须显式置为「完整」再被任一不完整 SKU 拉低，
        // 否则合计永远显示「不完整」，与逐行结果自相矛盾
        totals.setProfitIsComplete(true);

        for (Map.Entry<String, List<SettlementDetail>> entry : bySku.entrySet()) {
            SkuProfitReport.SkuProfit profit = aggregate(entry.getKey(), entry.getValue());
            if (costQueried < maxCostQuerySkus) {
                costQueried++;
                applyCost(shopId, entry.getKey(), profit);
            } else {
                profit.setCostMissing(true);
                profit.setProfitIsComplete(false);
                profit.getDataNotes().add("超出单次成本查询上限（" + maxCostQuerySkus + " 个 SKU），本次未取成本");
            }
            if (profit.isCostMissing()) {
                costMissingCount++;
                if (costMissingSkus.size() < 10) {
                    costMissingSkus.add(entry.getKey());
                }
            }
            report.getEntries().add(profit);
            accumulate(totals, profit);
        }

        report.setIncompleteSkuCount(costMissingCount);
        report.setCostDataComplete(costMissingCount == 0 && !truncated);
        report.setTotals(totals);
        if (costMissingCount > 0) {
            report.addWarning("有 " + costMissingCount
                    + " 个 SKU 缺少采购成本数据，其利润未扣成本、不可当作真实利润："
                    + String.join(", ", costMissingSkus));
        }
        if (truncated) {
            report.addWarning("SKU 数超过 " + maxCostQuerySkus
                    + "，超出部分的成本未查询；建议按 SKU 维度分批查看或用时间窗口收窄范围");
        }
        if (unattributed > 0) {
            report.addWarning("有 " + unattributed + " 条结算行无 SKU（平台调整行等），未计入单品利润");
        }
        report.addWarning("成本口径为「批次加权平均单位成本 × 售出件数」，是 FIFO 的近似；"
                + "批次成本已含头程运费与关税，未再叠加物流头程分摊（避免重复计费）");
        return report;
    }

    /**
     * 聚合单个 SKU 的收入与费用侧（不含成本）。
     */
    private SkuProfitReport.SkuProfit aggregate(String sku, List<SettlementDetail> rows) {
        SkuProfitReport.SkuProfit profit = new SkuProfitReport.SkuProfit();
        profit.setSku(sku);
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal fulfillment = BigDecimal.ZERO;
        BigDecimal storage = BigDecimal.ZERO;
        BigDecimal otherFees = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal reimbursed = BigDecimal.ZERO;
        int units = 0;

        for (SettlementDetail row : rows) {
            BigDecimal amount = nz(row.getAmount());
            String type = row.getAmountType() == null ? "" : row.getAmountType().toLowerCase();
            String txType = row.getTransactionType() == null ? "" : row.getTransactionType();

            if ("Refund".equalsIgnoreCase(txType)) {
                refunded = refunded.add(amount.abs());
                continue;
            }
            if ("Adjustment".equalsIgnoreCase(txType)) {
                reimbursed = reimbursed.add(amount);
                continue;
            }
            if (type.contains("storage")) {
                storage = storage.add(amount.abs());
                continue;
            }
            if (type.contains("commission")) {
                commission = commission.add(amount.abs());
                continue;
            }
            if (type.contains("fulfillment")) {
                fulfillment = fulfillment.add(amount.abs());
                continue;
            }
            if ("Order".equalsIgnoreCase(txType) && type.contains("principal")) {
                revenue = revenue.add(amount);
                units++;
                continue;
            }
            // 其余 Order 类型行（如 Shipping、GiftWrap）按其他平台费用处理
            otherFees = otherFees.add(amount.abs());
        }

        profit.setUnitsSold(units);
        profit.setRevenue(scale(revenue));
        profit.setCommission(scale(commission));
        profit.setFulfillmentFee(scale(fulfillment));
        profit.setStorageFee(scale(storage));
        profit.setOtherPlatformFees(scale(otherFees));
        profit.setRefunded(scale(refunded));
        profit.setReimbursed(scale(reimbursed));
        return profit;
    }

    /**
     * 取采购批次成本并套用到利润上；取不到时置 {@code costMissing}，不按 0 计但要算出一个
     * 明确标注为「未含成本」的中间值 —— 直接不给数字反而让人无法判断量级。
     */
    private void applyCost(Long shopId, String sku, SkuProfitReport.SkuProfit profit) {
        List<RemoteInventoryBatch> batches;
        try {
            Result<List<RemoteInventoryBatch>> result = procurementCostClient.listBatches(shopId, sku);
            if (result == null || result.getCode() != 200) {
                markCostMissing(profit, "采购成本服务不可用"
                        + (result == null ? "" : "：" + result.getMessage()));
                return;
            }
            batches = result.getData();
        } catch (Exception e) {
            markCostMissing(profit, "查询采购批次异常：" + e.getMessage());
            return;
        }
        if (batches == null || batches.isEmpty()) {
            markCostMissing(profit, "该 SKU 无采购批次成本记录");
            return;
        }

        BigDecimal totalBatchCost = BigDecimal.ZERO;
        int totalQuantity = 0;
        for (RemoteInventoryBatch batch : batches) {
            BigDecimal qty = batch.getQuantity() == null ? BigDecimal.ZERO
                    : BigDecimal.valueOf(batch.getQuantity());
            if (qty.signum() <= 0) {
                continue;
            }
            BigDecimal batchTotal = batch.getTotalCost() != null ? batch.getTotalCost()
                    : nz(batch.getUnitCost()).multiply(qty)
                            .add(nz(batch.getFreightCost())).add(nz(batch.getCustomsCost()))
                            .add(nz(batch.getOtherCost()));
            totalBatchCost = totalBatchCost.add(batchTotal);
            totalQuantity += batch.getQuantity();
        }
        if (totalQuantity <= 0) {
            markCostMissing(profit, "采购批次数量合计为 0，无法计算单位成本");
            return;
        }
        BigDecimal avgUnitCost = totalBatchCost
                .divide(BigDecimal.valueOf(totalQuantity), 4, RoundingMode.HALF_UP);
        BigDecimal cogs = avgUnitCost.multiply(BigDecimal.valueOf(profit.getUnitsSold()))
                .setScale(2, RoundingMode.HALF_UP);

        profit.setAvgUnitCost(avgUnitCost);
        profit.setCogs(cogs);
        profit.setCostMissing(false);
        profit.getDataNotes().add("成本 = 批次加权平均单位成本 " + avgUnitCost.toPlainString()
                + " × 售出 " + profit.getUnitsSold() + " 件（FIFO 近似）");
        finishProfit(profit, true);
    }

    private void markCostMissing(SkuProfitReport.SkuProfit profit, String note) {
        profit.setCostMissing(true);
        profit.setCogs(BigDecimal.ZERO);
        profit.getDataNotes().add(note);
        finishProfit(profit, false);
    }

    /**
     * 计算利润、单件利润与利润率。利润率在收入为 0 时为 null —— 不能是 0%。
     */
    private static void finishProfit(SkuProfitReport.SkuProfit profit, boolean costAvailable) {
        BigDecimal value = profit.getRevenue()
                .subtract(profit.getCommission())
                .subtract(profit.getFulfillmentFee())
                .subtract(profit.getStorageFee())
                .subtract(profit.getOtherPlatformFees())
                .subtract(profit.getRefunded())
                .subtract(profit.getCogs())
                .add(profit.getReimbursed())
                .setScale(2, RoundingMode.HALF_UP);
        profit.setProfit(value);
        profit.setProfitIsComplete(costAvailable);
        if (profit.getUnitsSold() > 0) {
            profit.setProfitPerUnit(value.divide(BigDecimal.valueOf(profit.getUnitsSold()),
                    2, RoundingMode.HALF_UP));
        }
        if (profit.getRevenue().signum() > 0) {
            profit.setMarginRate(value.divide(profit.getRevenue(), 4, RoundingMode.HALF_UP));
        } else {
            profit.setMarginRate(null);
            profit.getDataNotes().add("该 SKU 窗口内无销售收入，利润率不可计算");
        }
    }

    private static void accumulate(SkuProfitReport.SkuProfit totals, SkuProfitReport.SkuProfit p) {
        totals.setUnitsSold(totals.getUnitsSold() + p.getUnitsSold());
        totals.setRevenue(totals.getRevenue().add(p.getRevenue()));
        totals.setCommission(totals.getCommission().add(p.getCommission()));
        totals.setFulfillmentFee(totals.getFulfillmentFee().add(p.getFulfillmentFee()));
        totals.setStorageFee(totals.getStorageFee().add(p.getStorageFee()));
        totals.setOtherPlatformFees(totals.getOtherPlatformFees().add(p.getOtherPlatformFees()));
        totals.setRefunded(totals.getRefunded().add(p.getRefunded()));
        totals.setReimbursed(totals.getReimbursed().add(p.getReimbursed()));
        totals.setCogs(totals.getCogs().add(p.getCogs()));
        totals.setProfit(totals.getProfit().add(p.getProfit()));
        if (!p.isProfitIsComplete()) {
            totals.setProfitIsComplete(false);
        }
        if (p.isCostMissing()) {
            totals.setCostMissing(true);
        }
    }

    private static boolean inWindow(String value, String after, String before) {
        if (value == null || value.isBlank()) {
            return after == null && before == null;
        }
        if (after != null && !after.isBlank() && value.compareTo(after) < 0) {
            return false;
        }
        return before == null || before.isBlank() || value.compareTo(before) <= 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
