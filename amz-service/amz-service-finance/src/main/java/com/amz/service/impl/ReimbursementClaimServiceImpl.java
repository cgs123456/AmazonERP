package com.amz.service.impl;

import com.amz.context.UserContext;
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
import com.amz.service.ReimbursementClaimService;
import com.amz.util.BizNoGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索赔单服务实现（T07）与追回入账（T08）。
 * <p>
 * <b>状态机白名单</b>：{@code CANDIDATE → SUBMITTED → ACCEPTED → REIMBURSED}，
 * 中途可 {@code REJECTED}。跳过前置一律拒绝 —— 否则「已赔付」可以直接从「候选」跳过去，
 * 统计出来的追回金额全是假的。
 * <p>
 * <b>幂等</b>：重复执行同一动作返回当前状态，不报错、不重复产生副作用（尤其不重复生成凭证）。
 * <p>
 * <b>越权</b>：所有以业务主键为入参的接口（submit/accept/reimburse/reject）都在
 * {@link #requireClaim} 做归属校验，统一「不存在或无权访问」——
 * 不区分「不存在」与「无权限」，避免探测他店单据是否存在。
 */
@Slf4j
@Service
public class ReimbursementClaimServiceImpl implements ReimbursementClaimService {

    /** 状态机白名单：当前状态 → 允许的下一个状态。 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            ReimbursementClaim.STATUS_CANDIDATE,
            Set.of(ReimbursementClaim.STATUS_SUBMITTED),
            ReimbursementClaim.STATUS_SUBMITTED,
            Set.of(ReimbursementClaim.STATUS_ACCEPTED, ReimbursementClaim.STATUS_REJECTED),
            ReimbursementClaim.STATUS_ACCEPTED,
            Set.of(ReimbursementClaim.STATUS_REIMBURSED, ReimbursementClaim.STATUS_REJECTED),
            ReimbursementClaim.STATUS_REIMBURSED, Set.of(),
            ReimbursementClaim.STATUS_REJECTED, Set.of());

    @Autowired
    private ReimbursementClaimMapper reimbursementClaimMapper;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private FeeDiscrepancyService feeDiscrepancyService;

    @Autowired
    private FinanceService financeService;

    @Override
    public Long createFromDiscrepancy(Long shopId, Long discrepancyId) {
        if (shopId == null || discrepancyId == null) {
            throw new IllegalArgumentException("shopId and discrepancyId must not be null");
        }
        ReimbursementClaim existing = reimbursementClaimMapper.selectOne(
                new LambdaQueryWrapper<ReimbursementClaim>()
                        .eq(ReimbursementClaim::getShopId, shopId)
                        .eq(ReimbursementClaim::getDiscrepancyId, discrepancyId)
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("claim already exists for discrepancy, idempotent return: claimNo={}", existing.getClaimNo());
            return existing.getId();
        }

        FeeDiscrepancy d = feeDiscrepancyService.get(shopId, discrepancyId);
        if (d == null) {
            throw new IllegalArgumentException("差异记录不存在或无权访问");
        }
        if (FeeDiscrepancy.STATUS_DISMISSED.equals(d.getStatus())) {
            throw new IllegalStateException("该差异已被排除，不能生成索赔单");
        }
        BigDecimal claimAmount = d.getDifference() == null
                ? BigDecimal.ZERO : d.getDifference().abs().setScale(2, RoundingMode.HALF_UP);
        if (claimAmount.signum() <= 0) {
            throw new IllegalStateException("差异差额为 0，无可索赔金额");
        }

        ReimbursementClaim claim = new ReimbursementClaim();
        claim.setShopId(shopId);
        claim.setClaimNo(BizNoGenerator.next("CLM"));
        claim.setDiscrepancyId(discrepancyId);
        claim.setDiscrepancyType(d.getDiscrepancyType());
        claim.setSku(d.getSku());
        claim.setShipmentId(d.getShipmentId());
        claim.setClaimReason(d.getEvidence());
        claim.setClaimAmount(claimAmount);
        claim.setCurrency(d.getCurrency());
        claim.setStatus(ReimbursementClaim.STATUS_CANDIDATE);
        claim.setOperatorId(currentUserId());
        claim.setCreateTime(LocalDateTime.now());
        reimbursementClaimMapper.insert(claim);

        // 候选置为已索赔，避免同笔钱再生成第二张索赔单
        feeDiscrepancyService.updateStatus(shopId, discrepancyId, FeeDiscrepancy.STATUS_CLAIMED);
        log.info("claim created from discrepancy shopId={} discrepancyId={} claimNo={} amount={}",
                shopId, discrepancyId, claim.getClaimNo(), claimAmount);
        return claim.getId();
    }

    @Override
    public ReimbursementClaim submit(Long shopId, Long id) {
        ReimbursementClaim c = requireClaim(shopId, id);
        if (ReimbursementClaim.STATUS_SUBMITTED.equals(c.getStatus())) {
            return c;
        }
        validateTransition(c.getStatus(), ReimbursementClaim.STATUS_SUBMITTED);
        c.setStatus(ReimbursementClaim.STATUS_SUBMITTED);
        c.setSubmittedAt(LocalDateTime.now());
        c.setOperatorId(currentUserId());
        reimbursementClaimMapper.updateById(c);
        log.info("claim submitted claimNo={}", c.getClaimNo());
        return c;
    }

    @Override
    public ReimbursementClaim accept(Long shopId, Long id) {
        ReimbursementClaim c = requireClaim(shopId, id);
        if (ReimbursementClaim.STATUS_ACCEPTED.equals(c.getStatus())) {
            return c;
        }
        validateTransition(c.getStatus(), ReimbursementClaim.STATUS_ACCEPTED);
        c.setStatus(ReimbursementClaim.STATUS_ACCEPTED);
        c.setAcceptedAt(LocalDateTime.now());
        reimbursementClaimMapper.updateById(c);
        log.info("claim accepted claimNo={}", c.getClaimNo());
        return c;
    }

    @Override
    public ReimbursementClaim reimburse(Long shopId, Long id, BigDecimal reimbursedAmount) {
        if (reimbursedAmount == null || reimbursedAmount.signum() <= 0) {
            throw new IllegalArgumentException("reimbursedAmount must be positive");
        }
        ReimbursementClaim c = requireClaim(shopId, id);
        if (ReimbursementClaim.STATUS_REIMBURSED.equals(c.getStatus())) {
            // 幂等：已赔付时直接返回，绝不重复生成追回凭证（重复入账会虚增利润）
            log.info("claim already reimbursed, idempotent return: claimNo={}", c.getClaimNo());
            return c;
        }
        validateTransition(c.getStatus(), ReimbursementClaim.STATUS_REIMBURSED);

        c.setStatus(ReimbursementClaim.STATUS_REIMBURSED);
        c.setReimbursedAmount(reimbursedAmount.setScale(2, RoundingMode.HALF_UP));
        c.setSettledAt(LocalDateTime.now());
        if (c.getSubmittedAt() == null) {
            c.setSubmittedAt(LocalDateTime.now());
        }

        // T08：追回入账 —— 生成凭证并推金蝶
        try {
            AccountingVoucher voucher = financeService.generateReimbursementVoucher(
                    shopId, c.getClaimNo(), c.getReimbursedAmount(),
                    c.getCurrency() == null ? "USD" : c.getCurrency());
            if (voucher != null) {
                c.setVoucherId(voucher.getId());
                syncVoucherBestEffort(voucher.getId());
            }
        } catch (Exception e) {
            // 凭证失败不回滚赔付状态：钱确实到账了，账实不符要在对账里暴露而不是把赔付记录丢掉
            log.error("追回凭证生成失败 claimNo={} amount={}", c.getClaimNo(), c.getReimbursedAmount(), e);
        }

        reimbursementClaimMapper.updateById(c);
        if (c.getDiscrepancyId() != null) {
            feeDiscrepancyService.updateStatus(shopId, c.getDiscrepancyId(),
                    FeeDiscrepancy.STATUS_REIMBURSED);
        }
        log.info("claim reimbursed claimNo={} claimAmount={} reimbursedAmount={} voucherId={}",
                c.getClaimNo(), c.getClaimAmount(), c.getReimbursedAmount(), c.getVoucherId());
        return c;
    }

    @Override
    public ReimbursementClaim reject(Long shopId, Long id, String reason) {
        ReimbursementClaim c = requireClaim(shopId, id);
        if (ReimbursementClaim.STATUS_REJECTED.equals(c.getStatus())) {
            return c;
        }
        validateTransition(c.getStatus(), ReimbursementClaim.STATUS_REJECTED);
        c.setStatus(ReimbursementClaim.STATUS_REJECTED);
        c.setRejectReason(reason == null || reason.isBlank() ? "未填写原因" : reason);
        c.setSettledAt(LocalDateTime.now());
        reimbursementClaimMapper.updateById(c);
        // 驳回后把候选退回「已排除」，允许后续重新识别（材料补齐后可能成立）
        if (c.getDiscrepancyId() != null) {
            feeDiscrepancyService.updateStatus(shopId, c.getDiscrepancyId(),
                    FeeDiscrepancy.STATUS_DISMISSED);
        }
        log.info("claim rejected claimNo={} reason={}", c.getClaimNo(), c.getRejectReason());
        return c;
    }

    @Override
    public List<ReimbursementClaim> list(Long shopId, String status) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        LambdaQueryWrapper<ReimbursementClaim> qw = new LambdaQueryWrapper<ReimbursementClaim>()
                .eq(ReimbursementClaim::getShopId, shopId)
                .orderByDesc(ReimbursementClaim::getId);
        if (status != null && !status.isBlank()) {
            qw.eq(ReimbursementClaim::getStatus, status.toUpperCase());
        }
        return reimbursementClaimMapper.selectList(qw);
    }

    @Override
    public ReimbursementClaim get(Long shopId, Long id) {
        if (shopId == null || id == null) {
            throw new IllegalArgumentException("shopId and id must not be null");
        }
        ReimbursementClaim c = reimbursementClaimMapper.selectById(id);
        if (c == null || !shopId.equals(c.getShopId())) {
            return null;
        }
        return c;
    }

    @Override
    public ReimbursementClaimSummary summary(Long shopId) {
        List<ReimbursementClaim> claims = list(shopId, null);
        ReimbursementClaimSummary summary = new ReimbursementClaimSummary();
        summary.setShopId(shopId);
        summary.setTotal(claims.size());

        int closed = 0;
        int settledSamples = 0;
        long settledDays = 0L;
        for (ReimbursementClaim c : claims) {
            BigDecimal claimAmount = nz(c.getClaimAmount());
            BigDecimal reimbursedAmount = nz(c.getReimbursedAmount());
            summary.setClaimAmountTotal(summary.getClaimAmountTotal().add(claimAmount));
            summary.setReimbursedAmountTotal(summary.getReimbursedAmountTotal().add(reimbursedAmount));

            String status = c.getStatus() == null ? "" : c.getStatus();
            switch (status) {
                case ReimbursementClaim.STATUS_CANDIDATE:
                    summary.setCandidate(summary.getCandidate() + 1);
                    summary.setInFlightAmount(summary.getInFlightAmount().add(claimAmount));
                    break;
                case ReimbursementClaim.STATUS_SUBMITTED:
                    summary.setSubmitted(summary.getSubmitted() + 1);
                    summary.setInFlightAmount(summary.getInFlightAmount().add(claimAmount));
                    break;
                case ReimbursementClaim.STATUS_ACCEPTED:
                    summary.setAccepted(summary.getAccepted() + 1);
                    summary.setInFlightAmount(summary.getInFlightAmount().add(claimAmount));
                    break;
                case ReimbursementClaim.STATUS_REIMBURSED:
                    summary.setReimbursed(summary.getReimbursed() + 1);
                    closed++;
                    if (c.getSubmittedAt() != null && c.getSettledAt() != null) {
                        settledDays += ChronoUnit.DAYS.between(c.getSubmittedAt(), c.getSettledAt());
                        settledSamples++;
                    }
                    break;
                case ReimbursementClaim.STATUS_REJECTED:
                    summary.setRejected(summary.getRejected() + 1);
                    closed++;
                    break;
                default:
                    break;
            }
            ReimbursementClaimSummary.TypeStat stat = summary.getByType()
                    .computeIfAbsent(c.getDiscrepancyType() == null ? "UNKNOWN" : c.getDiscrepancyType(),
                            k -> new ReimbursementClaimSummary.TypeStat());
            stat.setClaims(stat.getClaims() + 1);
            stat.setClaimAmount(stat.getClaimAmount().add(claimAmount));
            if (ReimbursementClaim.STATUS_REIMBURSED.equals(status)) {
                stat.setReimbursed(stat.getReimbursed() + 1);
                stat.setReimbursedAmount(stat.getReimbursedAmount().add(reimbursedAmount));
            }
        }

        summary.setClaimAmountTotal(summary.getClaimAmountTotal().setScale(2, RoundingMode.HALF_UP));
        summary.setReimbursedAmountTotal(summary.getReimbursedAmountTotal().setScale(2, RoundingMode.HALF_UP));
        summary.setInFlightAmount(summary.getInFlightAmount().setScale(2, RoundingMode.HALF_UP));
        if (closed > 0) {
            summary.setSuccessRate(BigDecimal.valueOf(summary.getReimbursed())
                    .divide(BigDecimal.valueOf(closed), 4, RoundingMode.HALF_UP));
        } else {
            summary.addWarning("尚无已结案索赔（已赔付 + 已驳回），成功率不可计算 —— "
                    + "此处不返回 0%，0% 会被误读为「索赔全被驳回」");
        }
        if (settledSamples > 0) {
            summary.setAvgSettlementDays(BigDecimal.valueOf(settledDays)
                    .divide(BigDecimal.valueOf(settledSamples), 1, RoundingMode.HALF_UP));
        } else {
            summary.addWarning("尚无已赔付样本，平均赔付周期不可计算");
        }
        if (summary.getCandidate() > 0) {
            summary.addWarning("有 " + summary.getCandidate()
                    + " 张索赔单仍处于候选状态（未提交），这些钱还一分没要");
        }
        return summary;
    }

    @Override
    public ReimbursementReconcileReport reconcile(Long shopId, String depositAfter, String depositBefore) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        ReimbursementReconcileReport report = new ReimbursementReconcileReport();
        report.setShopId(shopId);

        // 平台侧：结算原表中的正向 Adjustment（FBA 库存赔付等，钱实际到账）
        List<SettlementDetail> details = settlementDetailMapper.selectList(
                new LambdaQueryWrapper<SettlementDetail>().eq(SettlementDetail::getShopId, shopId));
        List<ReimbursementReconcileReport.Item> platformItems = new ArrayList<>();
        for (SettlementDetail d : details) {
            if (!"Adjustment".equalsIgnoreCase(d.getTransactionType())) {
                continue;
            }
            if (d.getAmount() == null || d.getAmount().signum() <= 0) {
                continue;
            }
            if (!inWindow(d.getDepositDate(), depositAfter, depositBefore)) {
                continue;
            }
            platformItems.add(new ReimbursementReconcileReport.Item(
                    d.getAmountType() == null ? "Adjustment" : d.getAmountType(),
                    d.getSku(), d.getAmount(), d.getCurrency(), d.getDepositDate()));
        }
        report.setPlatformAdjustmentCount(platformItems.size());
        for (ReimbursementReconcileReport.Item item : platformItems) {
            report.setPlatformTotal(report.getPlatformTotal().add(nz(item.getAmount())));
        }

        // 系统侧：已赔付的索赔单
        List<ReimbursementReconcileReport.Item> systemItems = new ArrayList<>();
        for (ReimbursementClaim c : list(shopId, ReimbursementClaim.STATUS_REIMBURSED)) {
            if (!inWindow(c.getSettledAt() == null ? null : c.getSettledAt().toString(),
                    depositAfter, depositBefore)) {
                continue;
            }
            systemItems.add(new ReimbursementReconcileReport.Item(
                    c.getClaimNo(), c.getSku(), nz(c.getReimbursedAmount()), c.getCurrency(),
                    c.getSettledAt() == null ? null : c.getSettledAt().toString()));
        }
        for (ReimbursementReconcileReport.Item item : systemItems) {
            report.setSystemTotal(report.getSystemTotal().add(nz(item.getAmount())));
        }

        // 匹配：SKU + 金额（绝对值）相等即视为同一笔，双向各消耗一次
        Set<Integer> consumedSystem = new HashSet<>();
        int matched = 0;
        List<ReimbursementReconcileReport.Item> platformOnly = new ArrayList<>();
        for (ReimbursementReconcileReport.Item p : platformItems) {
            int hit = -1;
            for (int i = 0; i < systemItems.size(); i++) {
                if (consumedSystem.contains(i)) {
                    continue;
                }
                ReimbursementReconcileReport.Item s = systemItems.get(i);
                if (nz(s.getAmount()).compareTo(nz(p.getAmount()).abs()) == 0
                        && (p.getSku() == null ? s.getSku() == null : p.getSku().equals(s.getSku()))) {
                    hit = i;
                    break;
                }
            }
            if (hit >= 0) {
                consumedSystem.add(hit);
                matched++;
            } else {
                platformOnly.add(p);
            }
        }
        List<ReimbursementReconcileReport.Item> systemOnly = new ArrayList<>();
        for (int i = 0; i < systemItems.size(); i++) {
            if (!consumedSystem.contains(i)) {
                systemOnly.add(systemItems.get(i));
            }
        }

        report.setMatched(matched);
        report.setPlatformOnly(platformOnly);
        report.setSystemOnly(systemOnly);
        report.setPlatformTotal(report.getPlatformTotal().setScale(2, RoundingMode.HALF_UP));
        report.setSystemTotal(report.getSystemTotal().setScale(2, RoundingMode.HALF_UP));
        report.setDifference(report.getSystemTotal().subtract(report.getPlatformTotal()));

        if (!systemOnly.isEmpty()) {
            report.addWarning("有 " + systemOnly.size()
                    + " 笔系统已标记赔付但平台结算中未见对应调整 —— 账实不符，优先排查（可能漏拉结算报表或人工误标）");
        }
        if (!platformOnly.isEmpty()) {
            report.addWarning("有 " + platformOnly.size()
                    + " 笔平台赔付没有对应索赔单 —— 多为平台主动赔付，无需索赔动作，但需确认金额已计入收入");
        }
        if (report.getDifference().signum() != 0) {
            report.addWarning("两侧金额差 " + report.getDifference()
                    + "（系统 - 平台）：差额不等于错误，部分赔付与跨期到账都会造成差异，需人工确认");
        }
        return report;
    }

    /**
     * 存款日窗口过滤。窗口参数为 null 时不限制。
     */
    private static boolean inWindow(String value, String after, String before) {
        if (value == null || value.isBlank()) {
            // 无日期信息时不因窗口过滤丢弃，交由上层通过 warnings 关注
            return after == null && before == null;
        }
        if (after != null && !after.isBlank() && value.compareTo(after) < 0) {
            return false;
        }
        return before == null || before.isBlank() || value.compareTo(before) <= 0;
    }

    /**
     * 金蝶推送尽力而为：推送失败不回滚业务状态，凭证留在 PENDING 待重推。
     */
    private void syncVoucherBestEffort(Long voucherId) {
        try {
            boolean synced = financeService.syncToKingdee(voucherId);
            if (!synced) {
                log.warn("追回凭证推送金蝶未成功，保留 PENDING 待重推 voucherId={}", voucherId);
            }
        } catch (Exception e) {
            log.warn("追回凭证推送金蝶异常 voucherId={} reason={}", voucherId, e.getMessage());
        }
    }

    private ReimbursementClaim requireClaim(Long shopId, Long id) {
        ReimbursementClaim c = get(shopId, id);
        if (c == null) {
            throw new IllegalArgumentException("索赔单不存在或无权访问");
        }
        return c;
    }

    private static void validateTransition(String from, String to) {
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new IllegalStateException("索赔单状态不允许该操作：当前 " + from + " → 目标 " + to);
        }
    }

    private static Long currentUserId() {
        Integer userId = UserContext.getUserId();
        return userId == null ? null : userId.longValue();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
