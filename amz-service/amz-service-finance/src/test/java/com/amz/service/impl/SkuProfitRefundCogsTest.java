package com.amz.service.impl;

import com.amz.client.LogisticsReturnClient;
import com.amz.client.ProcurementCostClient;
import com.amz.client.dto.RemoteBatchCostSummary;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退货冲减 COGS 契约（退货闭环的「货」落到利润上）。
 * <p>
 * <b>存在动因</b>：此前退款只冲收入、不冲成本——{@code aggregate} 对 Refund 行
 * 只累加金额后 {@code continue}，件数不减；而 COGS = 单位成本 × 售出件数，
 * 于是退货件对应的成本永远留在利润里，售出成本虚高。
 * <p>
 * 本测试钉住：已收货的良品退货件数从计费件数里扣掉，COGS 随之下降；
 * 退货服务不可用时不冲减（保守：宁可成本偏高，也不因查不到就假设退了货），并在 notes 说明。
 * <p>
 * 变异验证（写本测试时实际注入确认会红）：把 {@code billableUnits} 改回 {@code unitsSold}
 * → 第一例红；把「服务失败时不冲减」改成按 0 冲减 → 第二例红。
 */
@DisplayName("退货冲减 COGS：良品退货件从计费件数扣除")
@ExtendWith(MockitoExtension.class)
class SkuProfitRefundCogsTest {

    private static final Long SHOP_ID = 1L;

    @Mock
    private SettlementDetailMapper settlementDetailMapper;
    @Mock
    private ProcurementCostClient procurementCostClient;
    @Mock
    private LogisticsReturnClient logisticsReturnClient;

    @InjectMocks
    private SkuProfitServiceImpl service;

    private static SettlementDetail sale(String sku, String amount) {
        SettlementDetail d = new SettlementDetail();
        d.setShopId(SHOP_ID);
        d.setSku(sku);
        d.setTransactionType("Order");
        d.setAmountType("Principal");
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("USD");
        return d;
    }

    private static RemoteBatchCostSummary cost(long quantity, String totalCost) {
        RemoteBatchCostSummary s = new RemoteBatchCostSummary();
        s.setBatchCount(1);
        s.setTotalQuantity(quantity);
        s.setTotalBatchCost(new BigDecimal(totalCost));
        return s;
    }

    private void stubSaleAndCost() {
        // 售出 10 件（10 行 principal），批次单位成本 10 元/件 → 全额 COGS = 100
        List<SettlementDetail> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(sale("SKU-1", "20.00"));
        }
        when(settlementDetailMapper.selectList(any())).thenReturn(rows);
        when(procurementCostClient.getCostSummary(eq(SHOP_ID), eq("SKU-1")))
                .thenReturn(Result.success(cost(100, "1000.00")));
    }

    @Test
    @DisplayName("3 件良品退货 → COGS 按 7 件计（10×7=70），而不是 10 件")
    void refundedGoodUnitsReduceCogs() {
        ReflectionTestUtils.setField(service, "maxCostQuerySkus", 100);
        stubSaleAndCost();
        when(logisticsReturnClient.returnedGoodUnits(SHOP_ID, "SKU-1"))
                .thenReturn(Result.success(3));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, "SKU-1");

        SkuProfitReport.SkuProfit profit = report.getEntries().get(0);
        assertEquals(10, profit.getUnitsSold(), "售出件数本身不变");
        assertEquals(0, new BigDecimal("70.00").compareTo(profit.getCogs()),
                "COGS 必须按 (10-3) 件计，实际 " + profit.getCogs());
        assertTrue(profit.getDataNotes().stream().anyMatch(n -> n.contains("良品退货 3")),
                "notes 应说明退货冲减，实际：" + profit.getDataNotes());
    }

    @Test
    @DisplayName("退货服务不可用：不冲减（保守），并在 notes 说明")
    void degradedReturnServiceDoesNotAdjust() {
        ReflectionTestUtils.setField(service, "maxCostQuerySkus", 100);
        stubSaleAndCost();
        when(logisticsReturnClient.returnedGoodUnits(SHOP_ID, "SKU-1"))
                .thenReturn(Result.failure("logistics degraded"));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, "SKU-1");

        SkuProfitReport.SkuProfit profit = report.getEntries().get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(profit.getCogs()),
                "查不到退货件数时按全部售出件计成本，不假设退了货");
        assertTrue(profit.getDataNotes().stream().anyMatch(n -> n.contains("未冲减退货成本")),
                "notes 必须说明未冲减，实际：" + profit.getDataNotes());
    }

    @Test
    @DisplayName("退货件数多于售出件：计费件数不为负")
    void neverNegativeBillableUnits() {
        ReflectionTestUtils.setField(service, "maxCostQuerySkus", 100);
        stubSaleAndCost();
        when(logisticsReturnClient.returnedGoodUnits(SHOP_ID, "SKU-1"))
                .thenReturn(Result.success(999));

        SkuProfitReport report = service.compute(SHOP_ID, null, null, "SKU-1");

        SkuProfitReport.SkuProfit profit = report.getEntries().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(profit.getCogs()),
                "计费件数下限为 0，不能为负导致成本变负数");
    }
}
