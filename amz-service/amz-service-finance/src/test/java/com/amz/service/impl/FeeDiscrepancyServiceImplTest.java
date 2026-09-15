package com.amz.service.impl;

import com.amz.client.SpApiFinanceClient;
import com.amz.client.dto.RemoteFeeEstimate;
import com.amz.dto.FeeDiscrepancyScanReport;
import com.amz.dto.InboundShortageRequest;
import com.amz.mapper.FeeDiscrepancyMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.FeeDiscrepancy;
import com.amz.model.SettlementDetail;
import com.amz.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 费用差异扫描单元测试（纯 Mockito）。
 * <p>
 * 重点验证：
 * <ol>
 *   <li>三条规则各自能独立命中（配送费多收 / 佣金多收 / 尺寸跳档）</li>
 *   <li><b>跳档优先于普通多收</b> —— 同一笔钱不能生成两条候选，否则索赔会重复申报</li>
 *   <li>阈值以下不生成候选（噪声不为空）</li>
 *   <li>同 SKU 同类型已有未结案候选时跳过（每日扫描不堆积）</li>
 *   <li>费用预估拿不到时明确「未评估」而不是当作「无差异」</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class FeeDiscrepancyServiceImplTest {

    private static final Long SHOP_ID = 1L;
    private static final String MARKETPLACE = "ATVPDKIKX0DER";

    @Mock
    private SpApiFinanceClient spApiFinanceClient;

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @Mock
    private FeeDiscrepancyMapper feeDiscrepancyMapper;

    @InjectMocks
    private FeeDiscrepancyServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "toleranceAmount", new BigDecimal("1.00"));
        ReflectionTestUtils.setField(service, "sizeJumpRatio", new BigDecimal("1.5"));
        ReflectionTestUtils.setField(service, "maxCandidates", 200);
    }

    private static SettlementDetail detail(String sku, String txType, String amountType, String amount) {
        SettlementDetail d = new SettlementDetail();
        d.setShopId(SHOP_ID);
        d.setSku(sku);
        d.setTransactionType(txType);
        d.setAmountType(amountType);
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("USD");
        return d;
    }

    private static RemoteFeeEstimate estimate(String fulfillment, String referral) {
        RemoteFeeEstimate e = new RemoteFeeEstimate();
        e.setFulfillmentFee(new BigDecimal(fulfillment));
        e.setReferralFee(new BigDecimal(referral));
        e.setOtherFees(BigDecimal.ZERO);
        e.setTotalFees(new BigDecimal(fulfillment).add(new BigDecimal(referral)));
        return e;
    }

    private void stubEstimate(String fulfillment, String referral) {
        when(spApiFinanceClient.estimateFees(eq(SHOP_ID), isNull(), eq("SKU"), anyString(), anyString(),
                any(BigDecimal.class), eq("USD")))
                .thenReturn(Result.success(estimate(fulfillment, referral)));
    }

    @Test
    @DisplayName("规则一：配送费高于预估 + 阈值 → 生成配送费多收候选")
    void detectsFulfillmentOvercharge() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail("SKU-A", "Order", "Principal", "29.99"),
                detail("SKU-A", "Order", "FBAPerUnitFulfillmentFee", "-8.00"))));
        stubEstimate("5.87", "4.50");

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(1, report.getScannedSkus());
        assertEquals(1, report.getCreated());
        assertEquals(1, report.getByType().get(FeeDiscrepancy.TYPE_FULFILLMENT_OVERCHARGE));
        assertEquals(0, new BigDecimal("2.13").compareTo(report.getClaimableAmount()),
                "8.00 - 5.87 = 2.13");

        ArgumentCaptor<FeeDiscrepancy> captor = ArgumentCaptor.forClass(FeeDiscrepancy.class);
        verify(feeDiscrepancyMapper).insert(captor.capture());
        FeeDiscrepancy saved = captor.getValue();
        assertEquals(0, new BigDecimal("5.87").compareTo(saved.getExpectedAmount()));
        assertEquals(0, new BigDecimal("8.00").compareTo(saved.getActualAmount()));
        assertEquals(0, new BigDecimal("2.13").compareTo(saved.getDifference()));
        assertEquals(FeeDiscrepancy.STATUS_CANDIDATE, saved.getStatus());
        assertTrue(saved.getEvidence().contains("预估配送费"));
        assertTrue(saved.getEvidence().contains("实际扣费"));
        assertNotNull(saved.getDetectedAt());
    }

    @Test
    @DisplayName("规则三优先：单件配送费跳档 1.77 倍 → 只生成尺寸跳档，不重复生成配送费多收")
    void tierJumpTakesPrecedence() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail("SKU-B", "Order", "Principal", "20.00"),
                detail("SKU-B", "Order", "FBAPerUnitFulfillmentFee", "-3.31"),
                detail("SKU-B", "Order", "FBAPerUnitFulfillmentFee", "-5.87"))));
        stubEstimate("3.31", "1.50");

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(1, report.getCreated());
        assertEquals(1, report.getByType().get(FeeDiscrepancy.TYPE_SIZE_TIER_JUMP));
        assertNull(report.getByType().get(FeeDiscrepancy.TYPE_FULFILLMENT_OVERCHARGE),
                "跳档已解释多收，不应再生成普通多收候选（否则索赔会重复申报同一笔钱）");
        ArgumentCaptor<FeeDiscrepancy> captor = ArgumentCaptor.forClass(FeeDiscrepancy.class);
        verify(feeDiscrepancyMapper).insert(captor.capture());
        assertTrue(captor.getValue().getEvidence().contains("跳档"), captor.getValue().getEvidence());
    }

    @Test
    @DisplayName("规则二：佣金高于预估 + 阈值 → 生成佣金多收候选")
    void detectsCommissionOvercharge() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail("SKU-C", "Order", "Principal", "100.00"),
                detail("SKU-C", "Order", "Commission", "-25.00"))));
        stubEstimate("5.87", "4.50");

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(1, report.getCreated());
        assertEquals(1, report.getByType().get(FeeDiscrepancy.TYPE_COMMISSION_OVERCHARGE));
        assertEquals(0, new BigDecimal("20.50").compareTo(report.getClaimableAmount()),
                "25.00 - 4.50 = 20.50");
    }

    @Test
    @DisplayName("阈值以下不生成候选，并明确告知「未超过阈值」")
    void belowToleranceIgnored() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail("SKU-D", "Order", "Principal", "29.99"),
                detail("SKU-D", "Order", "FBAPerUnitFulfillmentFee", "-6.37"))));
        stubEstimate("5.87", "4.50");

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(0, report.getCreated());
        verify(feeDiscrepancyMapper, never()).insert(any(FeeDiscrepancy.class));
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("未发现超过阈值")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("去重：同 SKU 同类型已有未结案候选时跳过，不重复堆积")
    void skippedWhenOpenCandidateExists() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail("SKU-A", "Order", "Principal", "29.99"),
                detail("SKU-A", "Order", "FBAPerUnitFulfillmentFee", "-8.00"))));
        stubEstimate("5.87", "4.50");
        when(feeDiscrepancyMapper.countOpenBySkuAndType(SHOP_ID, "SKU-A",
                FeeDiscrepancy.TYPE_FULFILLMENT_OVERCHARGE)).thenReturn(1);

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(0, report.getCreated());
        assertEquals(1, report.getSkippedExisting());
        verify(feeDiscrepancyMapper, never()).insert(any(FeeDiscrepancy.class));
    }

    @Test
    @DisplayName("费用预估不可用：记为「未评估」并给出 SKU，不能当成「无差异」")
    void estimateUnavailableIsNotZeroDifference() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail("SKU-A", "Order", "Principal", "29.99"),
                detail("SKU-A", "Order", "FBAPerUnitFulfillmentFee", "-8.00"))));
        when(spApiFinanceClient.estimateFees(eq(SHOP_ID), isNull(), eq("SKU"), anyString(), anyString(),
                any(BigDecimal.class), eq("USD")))
                .thenReturn(Result.failure("spapi finance service degraded: timeout"));

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(0, report.getCreated());
        assertEquals(1, report.getSkippedNoEstimate());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("SKU-A")),
                report.getWarnings().toString());
        verify(feeDiscrepancyMapper, never()).insert(any(FeeDiscrepancy.class));
    }

    @Test
    @DisplayName("缺少成交价（无 Principal 行）→ 记未评估而非按 0 估价")
    void missingPriceSkipsSku() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail("SKU-X", "Order", "FBAPerUnitFulfillmentFee", "-8.00"))));

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(0, report.getCreated());
        assertEquals(1, report.getSkippedNoEstimate());
        verify(spApiFinanceClient, never()).estimateFees(any(), any(), anyString(), anyString(),
                anyString(), any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("无 SKU 归属的结算行（平台调整）单独计数并提示")
    void unattributedRowsWarned() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                detail(null, "Adjustment", "FBA Inventory Reimbursement", "12.50"))));

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(0, report.getScannedSkus());
        assertEquals(1, report.getUnattributedRows());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("没有 SKU")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("无结算数据：提示先同步结算原表，不报错")
    void emptySettlementWarns() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>());

        FeeDiscrepancyScanReport report = service.scan(SHOP_ID, null);

        assertEquals(0, report.getCreated());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("请先同步结算原表")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("入库短收登记：差额为负（应给未给），金额 = 数量 × 单位成本")
    void intakeInboundShortage() {
        when(feeDiscrepancyMapper.countInboundShortage(SHOP_ID, "SHP-1", "SKU-R")).thenReturn(0);
        when(feeDiscrepancyMapper.insert(any(FeeDiscrepancy.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FeeDiscrepancy.class).setId(77L);
            return 1;
        });

        InboundShortageRequest request = new InboundShortageRequest();
        request.setSku("SKU-R");
        request.setShipmentId("SHP-1");
        request.setShortageUnits(3);
        request.setUnitAmount(new BigDecimal("4.50"));
        request.setCurrency("USD");
        request.setNote("物流签收差异 REC-88");

        Long id = service.intakeInboundShortage(SHOP_ID, request);

        assertEquals(77L, id);
        ArgumentCaptor<FeeDiscrepancy> captor = ArgumentCaptor.forClass(FeeDiscrepancy.class);
        verify(feeDiscrepancyMapper).insert(captor.capture());
        FeeDiscrepancy saved = captor.getValue();
        assertEquals(FeeDiscrepancy.TYPE_INBOUND_SHORTAGE, saved.getDiscrepancyType());
        assertEquals(0, new BigDecimal("13.50").compareTo(saved.getExpectedAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getActualAmount()), "尚未赔付 → 实际为 0");
        assertEquals(0, new BigDecimal("-13.50").compareTo(saved.getDifference()),
                "应给未给 → 差额为负");
        assertTrue(saved.getEvidence().contains("REC-88"));
    }

    @Test
    @DisplayName("入库短收重复登记：返回 null 且不写库")
    void intakeInboundShortageDuplicate() {
        when(feeDiscrepancyMapper.countInboundShortage(SHOP_ID, "SHP-1", "SKU-R")).thenReturn(1);

        InboundShortageRequest request = new InboundShortageRequest();
        request.setSku("SKU-R");
        request.setShipmentId("SHP-1");
        request.setShortageUnits(3);
        request.setUnitAmount(new BigDecimal("4.50"));

        assertNull(service.intakeInboundShortage(SHOP_ID, request));
        verify(feeDiscrepancyMapper, never()).insert(any(FeeDiscrepancy.class));
    }

    @Test
    @DisplayName("入库短收参数校验：SKU/货件/数量/单位成本缺一不可")
    void intakeValidation() {
        InboundShortageRequest ok = new InboundShortageRequest();
        ok.setSku("SKU-R");
        ok.setShipmentId("SHP-1");
        ok.setShortageUnits(1);
        ok.setUnitAmount(new BigDecimal("1.00"));
        assertNotNull(ok);

        InboundShortageRequest noSku = new InboundShortageRequest();
        noSku.setShipmentId("SHP-1");
        noSku.setShortageUnits(1);
        noSku.setUnitAmount(BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> service.intakeInboundShortage(SHOP_ID, noSku));

        InboundShortageRequest noShipment = new InboundShortageRequest();
        noShipment.setSku("SKU-R");
        noShipment.setShortageUnits(1);
        noShipment.setUnitAmount(BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> service.intakeInboundShortage(SHOP_ID, noShipment));

        InboundShortageRequest zeroUnits = new InboundShortageRequest();
        zeroUnits.setSku("SKU-R");
        zeroUnits.setShipmentId("SHP-1");
        zeroUnits.setShortageUnits(0);
        zeroUnits.setUnitAmount(BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> service.intakeInboundShortage(SHOP_ID, zeroUnits));

        InboundShortageRequest zeroAmount = new InboundShortageRequest();
        zeroAmount.setSku("SKU-R");
        zeroAmount.setShipmentId("SHP-1");
        zeroAmount.setShortageUnits(1);
        zeroAmount.setUnitAmount(BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> service.intakeInboundShortage(SHOP_ID, zeroAmount));

        assertThrows(IllegalArgumentException.class,
                () -> service.intakeInboundShortage(null, ok));
    }

    @Test
    @DisplayName("状态更新：跨店 id 被拒（归属校验），本店可更新；非法状态直接拒绝")
    void updateStatusEnforcesOwnership() {
        FeeDiscrepancy otherShop = new FeeDiscrepancy();
        otherShop.setId(5L);
        otherShop.setShopId(999L);
        otherShop.setStatus(FeeDiscrepancy.STATUS_CANDIDATE);
        when(feeDiscrepancyMapper.selectById(5L)).thenReturn(otherShop);

        assertFalse(service.updateStatus(SHOP_ID, 5L, FeeDiscrepancy.STATUS_DISMISSED),
                "他店记录不可更新");
        verify(feeDiscrepancyMapper, never()).updateById(any(FeeDiscrepancy.class));
        assertNull(service.get(SHOP_ID, 5L), "他店记录不可读取");

        FeeDiscrepancy mine = new FeeDiscrepancy();
        mine.setId(6L);
        mine.setShopId(SHOP_ID);
        mine.setStatus(FeeDiscrepancy.STATUS_CANDIDATE);
        when(feeDiscrepancyMapper.selectById(6L)).thenReturn(mine);
        when(feeDiscrepancyMapper.updateById(any(FeeDiscrepancy.class))).thenReturn(1);

        assertTrue(service.updateStatus(SHOP_ID, 6L, "dismissed"));
        assertEquals(FeeDiscrepancy.STATUS_DISMISSED, mine.getStatus());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(SHOP_ID, 6L, "NOT_A_STATUS"));
    }

    @Test
    @DisplayName("列表查询：状态与类型过滤透传；shopId 必填")
    void listFilters() {
        when(feeDiscrepancyMapper.selectList(any())).thenReturn(new ArrayList<>());
        assertTrue(service.list(SHOP_ID, "candidate", "size_tier_jump").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null));
        verify(feeDiscrepancyMapper, times(1)).selectList(any());
    }
}
