package com.amz.service.impl;

import com.amz.client.ProcurementCostClient;
import com.amz.client.dto.RemoteInventoryBatch;
import com.amz.dto.SkuProfitReport;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.SettlementDetail;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单品真实利润单元测试（T09）。
 * <p>
 * 重点验证「缺失数据不按 0 处理」这条底线：
 * 成本拿不到时利润必须标记为不完整，绝不能输出一个看起来很好的假利润率。
 */
@ExtendWith(MockitoExtension.class)
class SkuProfitServiceImplTest {

    private static final Long SHOP_ID = 1L;

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @Mock
    private ProcurementCostClient procurementCostClient;

    @InjectMocks
    private SkuProfitServiceImpl service;

    private void setMaxCostQuerySkus(int n) {
        ReflectionTestUtils.setField(service, "maxCostQuerySkus", n);
    }

    private static SettlementDetail row(String sku, String txType, String amountType, String amount,
                                        String depositDate) {
        SettlementDetail d = new SettlementDetail();
        d.setShopId(SHOP_ID);
        d.setSku(sku);
        d.setTransactionType(txType);
        d.setAmountType(amountType);
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("USD");
        d.setDepositDate(depositDate);
        return d;
    }

    private static RemoteInventoryBatch batch(int quantity, String totalCost) {
        RemoteInventoryBatch b = new RemoteInventoryBatch();
        b.setBatchNo("B-1");
        b.setQuantity(quantity);
        b.setTotalCost(new BigDecimal(totalCost));
        return b;
    }

    @Test
    @DisplayName("完整口径：收入扣佣金/配送/仓储/退款与成本，再加回赔付追回")
    void computesFullProfit() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "29.99", null),
                row("SKU-A", "Order", "Commission", "-4.50", null),
                row("SKU-A", "Order", "FBAPerUnitFulfillmentFee", "-5.03", null),
                row("SKU-A", "Order", "MonthlyStorageFee", "-0.50", null),
                row("SKU-A", "Refund", "Principal", "-29.99", null),
                row("SKU-A", "Adjustment", "FBA Inventory Reimbursement", "12.50", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-A"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(100, "500.00")))));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        assertEquals(1, report.getSkuCount());
        SkuProfitReport.SkuProfit p = report.getEntries().get(0);
        assertEquals(0, new BigDecimal("29.99").compareTo(p.getRevenue()));
        assertEquals(0, new BigDecimal("4.50").compareTo(p.getCommission()));
        assertEquals(0, new BigDecimal("5.03").compareTo(p.getFulfillmentFee()));
        assertEquals(0, new BigDecimal("0.50").compareTo(p.getStorageFee()));
        assertEquals(0, new BigDecimal("29.99").compareTo(p.getRefunded()));
        assertEquals(0, new BigDecimal("12.50").compareTo(p.getReimbursed()));
        assertEquals(1, p.getUnitsSold());
        assertEquals(0, new BigDecimal("5.0000").compareTo(p.getAvgUnitCost()), "500/100 = 5.00");
        assertEquals(0, new BigDecimal("5.00").compareTo(p.getCogs()), "5.00 × 1 件");
        assertEquals(0, new BigDecimal("-2.53").compareTo(p.getProfit()),
                "29.99-4.50-5.03-0.50-29.99-5.00+12.50 = -2.53");
        assertEquals(0, new BigDecimal("-2.53").compareTo(p.getProfitPerUnit()));
        assertFalse(p.isCostMissing());
        assertTrue(p.isProfitIsComplete());
        assertTrue(report.isCostDataComplete());
        assertEquals(0, report.getIncompleteSkuCount());
    }

    @Test
    @DisplayName("成本拿不到：标记利润不完整，不按 0 计成本，绝不输出假利润率")
    void missingCostMarksProfitIncomplete() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "100.00", null),
                row("SKU-A", "Order", "Commission", "-15.00", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-A"))
                .thenReturn(Result.failure("procurement service degraded: timeout"));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        SkuProfitReport.SkuProfit p = report.getEntries().get(0);
        assertTrue(p.isCostMissing());
        assertFalse(p.isProfitIsComplete(), "缺成本时利润必须标记为不完整");
        assertEquals(0, BigDecimal.ZERO.compareTo(p.getCogs()), "缺成本时 cogs 为 0 但已标记缺失");
        assertEquals(0, new BigDecimal("85.00").compareTo(p.getProfit()),
                "85.00 是「未扣成本」的中间值，必须带 costMissing 一起解读");
        assertFalse(report.isCostDataComplete());
        assertEquals(1, report.getIncompleteSkuCount());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("不可当作真实利润")),
                report.getWarnings().toString());
        assertTrue(p.getDataNotes().stream().anyMatch(n -> n.contains("不可用")), p.getDataNotes().toString());
    }

    @Test
    @DisplayName("无采购批次：同样按缺失处理，并说明是「无记录」而非「服务不可用」")
    void emptyBatchesTreatedAsMissing() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-NEW", "Order", "Principal", "50.00", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-NEW"))
                .thenReturn(Result.success(new ArrayList<>()));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        SkuProfitReport.SkuProfit p = report.getEntries().get(0);
        assertTrue(p.isCostMissing());
        assertTrue(p.getDataNotes().stream().anyMatch(n -> n.contains("无采购批次成本记录")),
                p.getDataNotes().toString());
    }

    @Test
    @DisplayName("成本查询异常：不抛出，按缺失处理（单 SKU 异常不毁掉整份报告）")
    void costExceptionDegradesGracefully() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "10.00", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-A"))
                .thenThrow(new RuntimeException("connection reset"));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        assertTrue(report.getEntries().get(0).isCostMissing());
        assertEquals(1, report.getSkuCount());
    }

    @Test
    @DisplayName("合计行：各分项与利润求和，任一 SKU 缺成本则合计也不完整")
    void totalsAggregate() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "30.00", null),
                row("SKU-B", "Order", "Principal", "20.00", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-A"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(10, "50.00")))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-B"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(10, "100.00")))));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        SkuProfitReport.SkuProfit t = report.getTotals();
        assertEquals("TOTAL", t.getSku());
        assertEquals(2, t.getUnitsSold());
        assertEquals(0, new BigDecimal("50.00").compareTo(t.getRevenue()));
        assertEquals(0, new BigDecimal("15.00").compareTo(t.getCogs()), "5.00 + 10.00");
        assertEquals(0, new BigDecimal("35.00").compareTo(t.getProfit()), "25.00 + 10.00");
        assertTrue(t.isProfitIsComplete());
    }

    @Test
    @DisplayName("窗口过滤：窗口外结算行不计入")
    void appliesWindow() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "10.00", "2026-09-08T00:00:00Z"),
                row("SKU-A", "Order", "Principal", "99.00", "2026-10-08T00:00:00Z"))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-A"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(10, "10.00")))));

        SkuProfitReport report = service.compute(SHOP_ID,
                "2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z", null);

        SkuProfitReport.SkuProfit p = report.getEntries().get(0);
        assertEquals(0, new BigDecimal("10.00").compareTo(p.getRevenue()));
        assertEquals(1, p.getUnitsSold());
    }

    @Test
    @DisplayName("指定 SKU：只算该 SKU，且只查一次成本")
    void filtersBySku() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "10.00", null),
                row("SKU-B", "Order", "Principal", "20.00", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-B"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(1, "5.00")))));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, "SKU-B");

        assertEquals(1, report.getSkuCount());
        assertEquals("SKU-B", report.getEntries().get(0).getSku());
        verify(procurementCostClient, times(1)).listBatches(any(), anyString());
        verify(procurementCostClient, never()).listBatches(SHOP_ID, "SKU-A");
    }

    @Test
    @DisplayName("成本查询上限：超出部分标缺失并提示分批查看（避免一次报表打出上千次调用）")
    void capsCostQueries() {
        setMaxCostQuerySkus(1);
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "10.00", null),
                row("SKU-B", "Order", "Principal", "20.00", null))));
        when(procurementCostClient.listBatches(eq(SHOP_ID), anyString()))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(1, "1.00")))));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        assertEquals(2, report.getSkuCount());
        verify(procurementCostClient, times(1)).listBatches(eq(SHOP_ID), anyString());
        assertFalse(report.isCostDataComplete());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("超过 1")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("无收入 SKU：利润率返回 null 并说明，而不是 0%")
    void nullMarginWhenNoRevenue() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-R", "Adjustment", "FBA Inventory Reimbursement", "12.50", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-R"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(10, "10.00")))));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        SkuProfitReport.SkuProfit p = report.getEntries().get(0);
        assertNull(p.getMarginRate(), "收入为 0 时利润率不可计算");
        assertNull(p.getProfitPerUnit(), "无销量时单件利润不可计算");
        assertEquals(0, new BigDecimal("12.50").compareTo(p.getProfit()));
        assertTrue(p.getDataNotes().stream().anyMatch(n -> n.contains("利润率不可计算")),
                p.getDataNotes().toString());
    }

    @Test
    @DisplayName("无结算数据：提示先同步结算原表")
    void emptySettlementWarns() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>());

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        assertEquals(0, report.getSkuCount());
        assertFalse(report.isCostDataComplete());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("请先同步结算原表")),
                report.getWarnings().toString());
        verify(procurementCostClient, never()).listBatches(any(), anyString());
    }

    @Test
    @DisplayName("无 SKU 归属行：单独提示，不静默丢弃")
    void unattributedRowsWarned() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row(null, "Adjustment", "FBA Inventory Reimbursement", "12.50", null),
                row("SKU-A", "Order", "Principal", "10.00", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-A"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(1, "1.00")))));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        assertEquals(1, report.getSkuCount());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("无 SKU")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("口径提示：报告始终说明成本是 FIFO 近似且未重复计头程运费")
    void alwaysExplainsCostConvention() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                row("SKU-A", "Order", "Principal", "10.00", null))));
        when(procurementCostClient.listBatches(SHOP_ID, "SKU-A"))
                .thenReturn(Result.success(new ArrayList<>(List.of(batch(1, "1.00")))));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, null);

        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("FIFO 的近似")),
                report.getWarnings().toString());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("未再叠加物流头程分摊")),
                report.getWarnings().toString());
        assertNotNull(report.getEntries().get(0).getAvgUnitCost());
    }

    @Test
    @DisplayName("shopId 为空：抛 IllegalArgumentException")
    void shopIdRequired() {
        assertThrows(IllegalArgumentException.class, () -> service.compute(null, null, null, null));
    }
}
