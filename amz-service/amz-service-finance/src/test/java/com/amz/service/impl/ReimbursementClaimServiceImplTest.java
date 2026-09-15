package com.amz.service.impl;

import com.amz.dto.ReimbursementClaimSummary;
import com.amz.dto.ReimbursementReconcileReport;
import com.amz.mapper.ReimbursementClaimMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.AccountingVoucher;
import com.amz.model.FeeDiscrepancy;
import com.amz.model.ReimbursementClaim;
import com.amz.model.SettlementDetail;
import com.amz.service.FeeDiscrepancyService;
import com.amz.service.FinanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索赔单服务单元测试（T07 / T08）。
 * <p>
 * 重点验证：
 * <ol>
 *   <li><b>状态机</b>：跳过前置一律拒绝（候选不能直接变已赔付 —— 否则追回金额全是假的）</li>
 *   <li><b>幂等</b>：重复赔付不重复生成追回凭证（重复入账 = 虚增利润）</li>
 *   <li><b>越权</b>：id 型入参必须做归属校验</li>
 *   <li><b>无样本不给 0</b>：成功率 / 赔付周期在无已结案样本时为 null 并附说明</li>
 *   <li><b>双向核对</b>：platformOnly 与 systemOnly 分开（前者无需动作，后者是账实不符）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ReimbursementClaimServiceImplTest {

    private static final Long SHOP_ID = 1L;

    @Mock
    private ReimbursementClaimMapper reimbursementClaimMapper;

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @Mock
    private FeeDiscrepancyService feeDiscrepancyService;

    @Mock
    private FinanceService financeService;

    @InjectMocks
    private ReimbursementClaimServiceImpl service;

    private static FeeDiscrepancy discrepancy(Long id, String status, String difference) {
        FeeDiscrepancy d = new FeeDiscrepancy();
        d.setId(id);
        d.setShopId(SHOP_ID);
        d.setSku("SKU-A");
        d.setDiscrepancyType(FeeDiscrepancy.TYPE_FULFILLMENT_OVERCHARGE);
        d.setDifference(new BigDecimal(difference));
        d.setCurrency("USD");
        d.setEvidence("预估配送费 5.87；实际扣费 8.00");
        d.setStatus(status);
        return d;
    }

    private static ReimbursementClaim claim(Long id, String status, String claimAmount,
                                            String reimbursedAmount) {
        ReimbursementClaim c = new ReimbursementClaim();
        c.setId(id);
        c.setShopId(SHOP_ID);
        c.setClaimNo("CLM-100" + id);
        c.setSku("SKU-A");
        c.setDiscrepancyType(FeeDiscrepancy.TYPE_FULFILLMENT_OVERCHARGE);
        c.setClaimAmount(claimAmount == null ? null : new BigDecimal(claimAmount));
        c.setReimbursedAmount(reimbursedAmount == null ? null : new BigDecimal(reimbursedAmount));
        c.setCurrency("USD");
        c.setStatus(status);
        return c;
    }

    private void stubClaimById(Long id, ReimbursementClaim c) {
        when(reimbursementClaimMapper.selectById(id)).thenReturn(c);
    }

    @Test
    @DisplayName("由候选建单：金额取差额绝对值，并把候选置为已索赔")
    void createFromDiscrepancy() {
        when(reimbursementClaimMapper.selectOne(any())).thenReturn(null);
        when(feeDiscrepancyService.get(SHOP_ID, 9L))
                .thenReturn(discrepancy(9L, FeeDiscrepancy.STATUS_CANDIDATE, "-13.50"));
        when(reimbursementClaimMapper.insert(any(ReimbursementClaim.class))).thenAnswer(inv -> {
            inv.getArgument(0, ReimbursementClaim.class).setId(31L);
            return 1;
        });

        Long id = service.createFromDiscrepancy(SHOP_ID, 9L);

        assertEquals(31L, id);
        ArgumentCaptor<ReimbursementClaim> captor = ArgumentCaptor.forClass(ReimbursementClaim.class);
        verify(reimbursementClaimMapper).insert(captor.capture());
        ReimbursementClaim saved = captor.getValue();
        assertEquals(0, new BigDecimal("13.50").compareTo(saved.getClaimAmount()),
                "差额为负（应给未给）时索赔金额取绝对值");
        assertEquals(ReimbursementClaim.STATUS_CANDIDATE, saved.getStatus());
        assertTrue(saved.getClaimNo().startsWith("CLM"), saved.getClaimNo());
        assertNull(saved.getSubmittedAt(), "建单不等于已提交");
        verify(feeDiscrepancyService).updateStatus(SHOP_ID, 9L, FeeDiscrepancy.STATUS_CLAIMED);
    }

    @Test
    @DisplayName("由候选建单：同一候选重复建单幂等返回既有单")
    void createFromDiscrepancyIdempotent() {
        when(reimbursementClaimMapper.selectOne(any()))
                .thenReturn(claim(31L, ReimbursementClaim.STATUS_CANDIDATE, "13.50", null));

        assertEquals(31L, service.createFromDiscrepancy(SHOP_ID, 9L));
        verify(reimbursementClaimMapper, never()).insert(any(ReimbursementClaim.class));
        verify(feeDiscrepancyService, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("由候选建单：已排除的候选 / 候选不存在 / 差额为 0 均拒绝")
    void createFromDiscrepancyRejections() {
        when(reimbursementClaimMapper.selectOne(any())).thenReturn(null);

        when(feeDiscrepancyService.get(SHOP_ID, 1L))
                .thenReturn(discrepancy(1L, FeeDiscrepancy.STATUS_DISMISSED, "10.00"));
        assertThrows(IllegalStateException.class, () -> service.createFromDiscrepancy(SHOP_ID, 1L));

        when(feeDiscrepancyService.get(SHOP_ID, 2L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.createFromDiscrepancy(SHOP_ID, 2L));

        when(feeDiscrepancyService.get(SHOP_ID, 3L))
                .thenReturn(discrepancy(3L, FeeDiscrepancy.STATUS_CANDIDATE, "0.00"));
        assertThrows(IllegalStateException.class, () -> service.createFromDiscrepancy(SHOP_ID, 3L));
    }

    @Test
    @DisplayName("提交：候选 → 已提交；重复提交幂等只写一次库")
    void submitAndIdempotency() {
        ReimbursementClaim c = claim(5L, ReimbursementClaim.STATUS_CANDIDATE, "13.50", null);
        stubClaimById(5L, c);
        when(reimbursementClaimMapper.updateById(any(ReimbursementClaim.class))).thenReturn(1);

        ReimbursementClaim submitted = service.submit(SHOP_ID, 5L);
        assertEquals(ReimbursementClaim.STATUS_SUBMITTED, submitted.getStatus());
        assertNotNull(submitted.getSubmittedAt());
        verify(reimbursementClaimMapper, times(1)).updateById(any(ReimbursementClaim.class));

        // 第二次调用：状态已是 SUBMITTED，应直接返回不写库
        ReimbursementClaim again = service.submit(SHOP_ID, 5L);
        assertEquals(ReimbursementClaim.STATUS_SUBMITTED, again.getStatus());
        verify(reimbursementClaimMapper, times(1)).updateById(any(ReimbursementClaim.class));
    }

    @Test
    @DisplayName("状态机：跳过前置被拒（候选不能直接受理/赔付；终态不可再变）")
    void stateMachineRejectsSkips() {
        ReimbursementClaim candidate = claim(5L, ReimbursementClaim.STATUS_CANDIDATE, "13.50", null);
        stubClaimById(5L, candidate);
        assertThrows(IllegalStateException.class, () -> service.accept(SHOP_ID, 5L));
        assertThrows(IllegalStateException.class,
                () -> service.reimburse(SHOP_ID, 5L, new BigDecimal("13.50")));
        assertThrows(IllegalStateException.class, () -> service.reject(SHOP_ID, 5L, "证据不足"));
        verify(reimbursementClaimMapper, never()).updateById(any(ReimbursementClaim.class));

        ReimbursementClaim reimbursed = claim(6L, ReimbursementClaim.STATUS_REIMBURSED, "13.50", "13.50");
        stubClaimById(6L, reimbursed);
        assertThrows(IllegalStateException.class, () -> service.submit(SHOP_ID, 6L));
        assertThrows(IllegalStateException.class, () -> service.accept(SHOP_ID, 6L));
    }

    @Test
    @DisplayName("赔付：生成追回凭证 + 推金蝶 + 候选转已赔付")
    void reimburseCreatesVoucher() {
        ReimbursementClaim c = claim(7L, ReimbursementClaim.STATUS_ACCEPTED, "13.50", null);
        c.setDiscrepancyId(9L);
        stubClaimById(7L, c);
        AccountingVoucher voucher = new AccountingVoucher();
        voucher.setId(88L);
        when(financeService.generateReimbursementVoucher(eq(SHOP_ID), any(), any(), eq("USD")))
                .thenReturn(voucher);
        when(financeService.syncToKingdee(88L)).thenReturn(true);
        when(reimbursementClaimMapper.updateById(any(ReimbursementClaim.class))).thenReturn(1);

        ReimbursementClaim done = service.reimburse(SHOP_ID, 7L, new BigDecimal("12.00"));

        assertEquals(ReimbursementClaim.STATUS_REIMBURSED, done.getStatus());
        assertEquals(0, new BigDecimal("12.00").compareTo(done.getReimbursedAmount()),
                "允许部分赔付：实赔少于申请");
        assertEquals(0, new BigDecimal("13.50").compareTo(done.getClaimAmount()));
        assertEquals(88L, done.getVoucherId());
        assertNotNull(done.getSettledAt());
        verify(financeService).generateReimbursementVoucher(eq(SHOP_ID), eq("CLM-1007"),
                any(BigDecimal.class), eq("USD"));
        verify(financeService).syncToKingdee(88L);
        verify(feeDiscrepancyService).updateStatus(SHOP_ID, 9L, FeeDiscrepancy.STATUS_REIMBURSED);
    }

    @Test
    @DisplayName("赔付幂等：重复赔付不重复生成凭证（重复入账会虚增利润）")
    void reimburseIsIdempotent() {
        ReimbursementClaim c = claim(7L, ReimbursementClaim.STATUS_REIMBURSED, "13.50", "12.00");
        stubClaimById(7L, c);

        ReimbursementClaim again = service.reimburse(SHOP_ID, 7L, new BigDecimal("12.00"));

        assertEquals(ReimbursementClaim.STATUS_REIMBURSED, again.getStatus());
        verify(financeService, never()).generateReimbursementVoucher(any(), any(), any(), any());
        verify(reimbursementClaimMapper, never()).updateById(any(ReimbursementClaim.class));
    }

    @Test
    @DisplayName("赔付：凭证生成失败不回滚赔付状态（钱已到账，账实不符交由对账暴露）")
    void reimburseKeepsStatusWhenVoucherFails() {
        ReimbursementClaim c = claim(7L, ReimbursementClaim.STATUS_ACCEPTED, "13.50", null);
        stubClaimById(7L, c);
        when(financeService.generateReimbursementVoucher(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("kingdee unavailable"));
        when(reimbursementClaimMapper.updateById(any(ReimbursementClaim.class))).thenReturn(1);

        ReimbursementClaim done = service.reimburse(SHOP_ID, 7L, new BigDecimal("13.50"));

        assertEquals(ReimbursementClaim.STATUS_REIMBURSED, done.getStatus());
        assertNull(done.getVoucherId(), "凭证失败时不得记录不存在的凭证 ID");
    }

    @Test
    @DisplayName("赔付金额非正：直接拒绝")
    void reimburseValidatesAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reimburse(SHOP_ID, 1L, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> service.reimburse(SHOP_ID, 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.reimburse(SHOP_ID, 1L, new BigDecimal("-1")));
    }

    @Test
    @DisplayName("驳回：记录原因与结案时间，候选退回「已排除」")
    void rejectRecordsReason() {
        ReimbursementClaim c = claim(8L, ReimbursementClaim.STATUS_SUBMITTED, "13.50", null);
        c.setDiscrepancyId(9L);
        stubClaimById(8L, c);
        when(reimbursementClaimMapper.updateById(any(ReimbursementClaim.class))).thenReturn(1);

        ReimbursementClaim rejected = service.reject(SHOP_ID, 8L, "  ");

        assertEquals(ReimbursementClaim.STATUS_REJECTED, rejected.getStatus());
        assertEquals("未填写原因", rejected.getRejectReason());
        assertNotNull(rejected.getSettledAt());
        verify(feeDiscrepancyService).updateStatus(SHOP_ID, 9L, FeeDiscrepancy.STATUS_DISMISSED);
    }

    @Test
    @DisplayName("越权：他店索赔单不可读、不可推进")
    void ownershipEnforced() {
        ReimbursementClaim otherShop = claim(9L, ReimbursementClaim.STATUS_CANDIDATE, "13.50", null);
        otherShop.setShopId(999L);
        stubClaimById(9L, otherShop);

        assertNull(service.get(SHOP_ID, 9L));
        assertThrows(IllegalArgumentException.class, () -> service.submit(SHOP_ID, 9L));
        verify(reimbursementClaimMapper, never()).updateById(any(ReimbursementClaim.class));
    }

    @Test
    @DisplayName("概览：有已结案样本时给出成功率与平均赔付周期")
    void summaryComputesRates() {
        ReimbursementClaim ok = claim(1L, ReimbursementClaim.STATUS_REIMBURSED, "10.00", "10.00");
        ok.setSubmittedAt(LocalDateTime.now().minusDays(6));
        ok.setSettledAt(LocalDateTime.now());
        ReimbursementClaim bad = claim(2L, ReimbursementClaim.STATUS_REJECTED, "20.00", null);
        ReimbursementClaim pending = claim(3L, ReimbursementClaim.STATUS_CANDIDATE, "5.00", null);
        when(reimbursementClaimMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(ok, bad, pending)));

        ReimbursementClaimSummary summary = service.summary(SHOP_ID);

        assertEquals(3, summary.getTotal());
        assertEquals(1, summary.getReimbursed());
        assertEquals(1, summary.getRejected());
        assertEquals(1, summary.getCandidate());
        assertEquals(0, new BigDecimal("35.00").compareTo(summary.getClaimAmountTotal()));
        assertEquals(0, new BigDecimal("10.00").compareTo(summary.getReimbursedAmountTotal()));
        assertEquals(0, new BigDecimal("5.00").compareTo(summary.getInFlightAmount()),
                "在途金额只含未结案（候选）");
        assertEquals(0, new BigDecimal("0.5").compareTo(summary.getSuccessRate()));
        assertEquals(0, new BigDecimal("6.0").compareTo(summary.getAvgSettlementDays()));
        assertTrue(summary.getWarnings().stream().anyMatch(w -> w.contains("仍处于候选状态")),
                summary.getWarnings().toString());
    }

    @Test
    @DisplayName("概览：无已结案样本时不返回 0%（0% 会被读成「全被驳回」）")
    void summaryWithoutClosedSamples() {
        when(reimbursementClaimMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                claim(3L, ReimbursementClaim.STATUS_SUBMITTED, "5.00", null))));

        ReimbursementClaimSummary summary = service.summary(SHOP_ID);

        assertNull(summary.getSuccessRate());
        assertNull(summary.getAvgSettlementDays());
        assertTrue(summary.getWarnings().stream().anyMatch(w -> w.contains("成功率不可计算")),
                summary.getWarnings().toString());
    }

    @Test
    @DisplayName("双向核对：匹配上的成对、平台单边与系统单边分开列示")
    void reconcileSeparatesBothDirections() {
        List<SettlementDetail> details = new ArrayList<>();
        details.add(adjustment("SKU-A", "12.50"));   // 平台赔付，系统有对应索赔 → 匹配
        details.add(adjustment("SKU-B", "8.00"));    // 仅平台 → platformOnly
        when(settlementDetailMapper.selectList(any())).thenReturn(details);

        ReimbursementClaim matched = claim(1L, ReimbursementClaim.STATUS_REIMBURSED, "12.50", "12.50");
        matched.setSku("SKU-A");
        matched.setSettledAt(LocalDateTime.now());
        ReimbursementClaim orphan = claim(2L, ReimbursementClaim.STATUS_REIMBURSED, "30.00", "30.00");
        orphan.setSku("SKU-C");
        orphan.setSettledAt(LocalDateTime.now());
        when(reimbursementClaimMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(matched, orphan)));

        ReimbursementReconcileReport report = service.reconcile(SHOP_ID, null, null);

        assertEquals(2, report.getPlatformAdjustmentCount());
        assertEquals(1, report.getMatched());
        assertEquals(1, report.getPlatformOnly().size());
        assertEquals("SKU-B", report.getPlatformOnly().get(0).getSku());
        assertEquals(1, report.getSystemOnly().size());
        assertEquals("SKU-C", report.getSystemOnly().get(0).getSku());
        // platformTotal 是平台侧全部调整的合计（含未匹配的 8.00），不是只算匹配上的部分
        assertEquals(0, new BigDecimal("20.50").compareTo(report.getPlatformTotal()));
        assertEquals(0, new BigDecimal("42.50").compareTo(report.getSystemTotal()));
        assertEquals(0, new BigDecimal("22.00").compareTo(report.getDifference()),
                "差额 = 系统 - 平台 = 42.50 - 20.50");
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("账实不符")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("双向核对：窗口过滤生效（窗口外不计入）")
    void reconcileAppliesWindow() {
        SettlementDetail inWindow = adjustment("SKU-A", "5.00");
        inWindow.setDepositDate("2026-09-08T00:00:00Z");
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(inWindow)));
        when(reimbursementClaimMapper.selectList(any())).thenReturn(new ArrayList<>());

        ReimbursementReconcileReport inRange = service.reconcile(SHOP_ID,
                "2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");
        assertEquals(1, inRange.getPlatformAdjustmentCount());

        ReimbursementReconcileReport outOfRange = service.reconcile(SHOP_ID,
                "2026-10-01T00:00:00Z", null);
        assertEquals(0, outOfRange.getPlatformAdjustmentCount());
        assertFalse(outOfRange.getWarnings().stream().anyMatch(w -> w.contains("账实不符")));
    }

    @Test
    @DisplayName("列表过滤与参数校验")
    void listAndValidation() {
        when(reimbursementClaimMapper.selectList(any())).thenReturn(new ArrayList<>());
        assertTrue(service.list(SHOP_ID, "reimbursed").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null));
        assertThrows(IllegalArgumentException.class, () -> service.get(null, 1L));
    }

    private static SettlementDetail adjustment(String sku, String amount) {
        SettlementDetail d = new SettlementDetail();
        d.setShopId(SHOP_ID);
        d.setSku(sku);
        d.setTransactionType("Adjustment");
        d.setAmountType("FBA Inventory Reimbursement");
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("USD");
        return d;
    }
}
