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
import com.amz.service.FeeDiscrepancyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 费用差异与短收候选实现（T06）。
 * <p>
 * 三条自动识别规则 + 一条登记式入口：
 * <ol>
 *   <li><b>配送费多收</b>：实际配送费 &gt; 预估配送费 × 件数 + 阈值</li>
 *   <li><b>佣金多收</b>：实际佣金 &gt; 预估佣金 × 件数 + 阈值</li>
 *   <li><b>尺寸重测跳档</b>：同一 SKU 单件配送费出现阶梯跳变（重测后按更大尺寸档计费）</li>
 *   <li><b>入库短收</b>：登记式（索赔金额需单位成本，属采购域数据，见 InboundShortageRequest 说明）</li>
 * </ol>
 * <b>跳档优先于普通多收</b>：同一 SKU 若已判定为尺寸跳档，就不再额外生成「配送费多收」——
 * 两者指向同一笔钱，同时存在会导致索赔链路重复申报。
 * <p>
 * <b>售价用平均成交价代理</b>：费用预估必须给一个售价，结算原表没有商品定价，
 * 这里用「该 SKU 本期 Principal 合计 ÷ 件数」作为估价基准，并在举证里写明，
 * 便于人工复核时判断这个代理是否合适。
 */
@Slf4j
@Service
public class FeeDiscrepancyServiceImpl implements FeeDiscrepancyService {

    /** 准入阈值：差额低于此值不生成候选（默认 1 单位币种，避免几分钱的噪声淹没真问题）。 */
    @Value("${amz.finance.discrepancy.tolerance-amount:1.00}")
    private BigDecimal toleranceAmount = new BigDecimal("1.00");

    /** 尺寸跳档判定倍数：单件配送费最高/最低 ≥ 该倍数视为跳档。 */
    @Value("${amz.finance.discrepancy.size-jump-ratio:1.5}")
    private BigDecimal sizeJumpRatio = new BigDecimal("1.5");

    /** 单次扫描最多生成候选数（防止异常数据导致候选爆炸）。 */
    @Value("${amz.finance.discrepancy.max-candidates:200}")
    private int maxCandidates = 200;

    @Autowired
    private SpApiFinanceClient spApiFinanceClient;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private FeeDiscrepancyMapper feeDiscrepancyMapper;

    @Override
    public FeeDiscrepancyScanReport scan(Long shopId, String marketplaceId) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        FeeDiscrepancyScanReport report = new FeeDiscrepancyScanReport();
        report.setShopId(shopId);
        report.setToleranceAmount(toleranceAmount);

        List<SettlementDetail> rows = settlementDetailMapper.selectList(
                new LambdaQueryWrapper<SettlementDetail>().eq(SettlementDetail::getShopId, shopId));
        if (rows == null || rows.isEmpty()) {
            report.addWarning("该店铺暂无结算明细，无法比对费用差异（请先同步结算原表）");
            return report;
        }
        report.setScannedSettlementRows(rows.size());

        Map<String, List<SettlementDetail>> bySku = new LinkedHashMap<>();
        for (SettlementDetail row : rows) {
            if (row.getSku() == null || row.getSku().isBlank()) {
                report.setUnattributedRows(report.getUnattributedRows() + 1);
                continue;
            }
            bySku.computeIfAbsent(row.getSku(), k -> new ArrayList<>()).add(row);
        }
        report.setScannedSkus(bySku.size());

        Set<String> noEstimateSkus = new LinkedHashSet<>();
        boolean truncated = false;
        for (Map.Entry<String, List<SettlementDetail>> entry : bySku.entrySet()) {
            if (report.getCreated() >= maxCandidates) {
                truncated = true;
                break;
            }
            scanOneSku(shopId, marketplaceId, entry.getKey(), entry.getValue(), report, noEstimateSkus);
        }

        report.setClaimableAmount(report.getClaimableAmount().setScale(2, RoundingMode.HALF_UP));
        if (report.getUnattributedRows() > 0) {
            report.addWarning("有 " + report.getUnattributedRows()
                    + " 条结算行没有 SKU（多为平台调整行），无法参与费用比对");
        }
        if (!noEstimateSkus.isEmpty()) {
            report.addWarning("有 " + noEstimateSkus.size()
                    + " 个 SKU 因费用预估不可用或缺少成交价而未能评估（未生成候选不等于没有差异）："
                    + String.join(", ", limit(noEstimateSkus, 10)));
        }
        if (truncated) {
            report.addWarning("候选生成已达上限 " + maxCandidates + " 条，本次提前结束；剩余 SKU 待下次扫描");
        }
        if (report.getCreated() == 0 && report.getWarnings().isEmpty()) {
            report.addWarning("本次未发现超过阈值 " + toleranceAmount + " 的费用差异");
        }
        log.info("fee discrepancy scan shopId={} skus={} created={} skippedExisting={} noEstimate={}",
                shopId, report.getScannedSkus(), report.getCreated(),
                report.getSkippedExisting(), report.getSkippedNoEstimate());
        return report;
    }

    /**
     * 单个 SKU 的三条规则判定。
     */
    private void scanOneSku(Long shopId, String marketplaceId, String sku, List<SettlementDetail> rows,
                            FeeDiscrepancyScanReport report, Set<String> noEstimateSkus) {
        BigDecimal principalSum = BigDecimal.ZERO;
        BigDecimal fulfillmentSum = BigDecimal.ZERO;
        BigDecimal commissionSum = BigDecimal.ZERO;
        List<BigDecimal> unitFulfillmentFees = new ArrayList<>();
        int principalRows = 0;
        String currency = null;

        for (SettlementDetail row : rows) {
            BigDecimal amount = nz(row.getAmount());
            String amountType = row.getAmountType() == null ? "" : row.getAmountType().toLowerCase();
            String txType = row.getTransactionType() == null ? "" : row.getTransactionType();
            if (currency == null && row.getCurrency() != null && !row.getCurrency().isBlank()) {
                currency = row.getCurrency();
            }
            if ("Order".equalsIgnoreCase(txType) && amountType.contains("principal")) {
                principalSum = principalSum.add(amount);
                principalRows++;
            }
            if (amountType.contains("fulfillment")) {
                BigDecimal unitFee = amount.abs();
                fulfillmentSum = fulfillmentSum.add(unitFee);
                unitFulfillmentFees.add(unitFee);
            }
            if (amountType.contains("commission")) {
                commissionSum = commissionSum.add(amount.abs());
            }
        }

        int units = !unitFulfillmentFees.isEmpty() ? unitFulfillmentFees.size()
                : Math.max(1, principalRows);
        if (principalSum.signum() <= 0) {
            report.setSkippedNoEstimate(report.getSkippedNoEstimate() + 1);
            noEstimateSkus.add(sku);
            return;
        }
        BigDecimal avgPrice = principalSum.divide(BigDecimal.valueOf(units), 2, RoundingMode.HALF_UP);

        RemoteFeeEstimate estimate;
        try {
            estimate = requireSuccess(spApiFinanceClient.estimateFees(
                    shopId, marketplaceId, "SKU", sku, sku, avgPrice, currency), "费用预估失败");
        } catch (Exception e) {
            report.setSkippedNoEstimate(report.getSkippedNoEstimate() + 1);
            noEstimateSkus.add(sku);
            log.warn("fee estimate unavailable shopId={} sku={} reason={}", shopId, sku, e.getMessage());
            return;
        }
        if (estimate == null) {
            report.setSkippedNoEstimate(report.getSkippedNoEstimate() + 1);
            noEstimateSkus.add(sku);
            return;
        }

        BigDecimal expectedFulfillment = nz(estimate.getFulfillmentFee())
                .multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedCommission = nz(estimate.getReferralFee())
                .multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fulfillmentDiff = fulfillmentSum.subtract(expectedFulfillment);
        BigDecimal commissionDiff = commissionSum.subtract(expectedCommission);

        // 规则 3 优先：跳档能解释多收，先判跳档以免同一笔钱生成两条候选
        boolean tierJump = detectTierJump(unitFulfillmentFees);
        if (tierJump) {
            BigDecimal max = unitFulfillmentFees.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            BigDecimal min = unitFulfillmentFees.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            String evidence = String.format(
                    "同一 SKU 单件配送费出现跳档：%s → %s（%.2f 倍，%d 件）；"
                            + "费用预估单件 %s，实际合计 %s（平均成交价 %s %s 代理定价）",
                    min.toPlainString(), max.toPlainString(),
                    min.signum() > 0 ? max.divide(min, 2, RoundingMode.HALF_UP).doubleValue() : 0d,
                    unitFulfillmentFees.size(), nz(estimate.getFulfillmentFee()).toPlainString(),
                    fulfillmentSum.toPlainString(), avgPrice.toPlainString(),
                    currency == null ? "USD" : currency);
            createCandidate(shopId, sku, null, FeeDiscrepancy.TYPE_SIZE_TIER_JUMP,
                    expectedFulfillment, fulfillmentSum, fulfillmentDiff, currency, evidence, report);
        } else if (fulfillmentDiff.compareTo(toleranceAmount) > 0) {
            String evidence = String.format(
                    "预估配送费 %s × %d 件 = %s；实际扣费 %s；多收 %s（平均成交价 %s %s 代理定价）",
                    nz(estimate.getFulfillmentFee()).toPlainString(), units,
                    expectedFulfillment.toPlainString(), fulfillmentSum.toPlainString(),
                    fulfillmentDiff.toPlainString(), avgPrice.toPlainString(),
                    currency == null ? "USD" : currency);
            createCandidate(shopId, sku, null, FeeDiscrepancy.TYPE_FULFILLMENT_OVERCHARGE,
                    expectedFulfillment, fulfillmentSum, fulfillmentDiff, currency, evidence, report);
        }

        if (commissionDiff.compareTo(toleranceAmount) > 0) {
            String evidence = String.format(
                    "预估佣金 %s × %d 件 = %s；实际扣费 %s；多收 %s（平均成交价 %s %s 代理定价）",
                    nz(estimate.getReferralFee()).toPlainString(), units,
                    expectedCommission.toPlainString(), commissionSum.toPlainString(),
                    commissionDiff.toPlainString(), avgPrice.toPlainString(),
                    currency == null ? "USD" : currency);
            createCandidate(shopId, sku, null, FeeDiscrepancy.TYPE_COMMISSION_OVERCHARGE,
                    expectedCommission, commissionSum, commissionDiff, currency, evidence, report);
        }
    }

    /**
     * 尺寸跳档判定：单件配送费最高 / 最低 ≥ 倍数阈值。
     * 少于两笔单件费用时无法判断（没有对比基准）。
     */
    private boolean detectTierJump(List<BigDecimal> unitFees) {
        if (unitFees.size() < 2) {
            return false;
        }
        BigDecimal min = unitFees.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal max = unitFees.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        if (min.signum() <= 0) {
            return false;
        }
        return max.divide(min, 4, RoundingMode.HALF_UP).compareTo(sizeJumpRatio) >= 0;
    }

    /**
     * 生成候选：先查同 SKU 同类型是否已有未结案候选，避免每日扫描重复堆积。
     */
    private void createCandidate(Long shopId, String sku, String shipmentId, String type,
                                 BigDecimal expected, BigDecimal actual, BigDecimal difference,
                                 String currency, String evidence, FeeDiscrepancyScanReport report) {
        if (feeDiscrepancyMapper.countOpenBySkuAndType(shopId, sku, type) > 0) {
            report.setSkippedExisting(report.getSkippedExisting() + 1);
            return;
        }
        FeeDiscrepancy d = new FeeDiscrepancy();
        d.setShopId(shopId);
        d.setSku(sku);
        d.setShipmentId(shipmentId);
        d.setDiscrepancyType(type);
        d.setExpectedAmount(expected);
        d.setActualAmount(actual);
        d.setDifference(difference);
        d.setCurrency(currency == null ? "USD" : currency);
        d.setEvidence(evidence);
        d.setStatus(FeeDiscrepancy.STATUS_CANDIDATE);
        d.setDetectedAt(LocalDateTime.now());
        d.setCreateTime(LocalDateTime.now());
        feeDiscrepancyMapper.insert(d);

        report.setCreated(report.getCreated() + 1);
        report.countType(type);
        report.setClaimableAmount(report.getClaimableAmount().add(difference.abs()));
        if (report.getCandidates().size() < 50) {
            report.getCandidates().add(d);
        }
    }

    @Override
    public Long intakeInboundShortage(Long shopId, InboundShortageRequest request) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        if (request == null || request.getSku() == null || request.getSku().isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (request.getShipmentId() == null || request.getShipmentId().isBlank()) {
            throw new IllegalArgumentException("shipmentId must not be blank");
        }
        if (request.getShortageUnits() == null || request.getShortageUnits() <= 0) {
            throw new IllegalArgumentException("shortageUnits must be positive");
        }
        if (request.getUnitAmount() == null || request.getUnitAmount().signum() <= 0) {
            throw new IllegalArgumentException("unitAmount must be positive");
        }
        if (feeDiscrepancyMapper.countInboundShortage(shopId, request.getShipmentId(), request.getSku()) > 0) {
            log.info("inbound shortage already registered shopId={} shipmentId={} sku={}",
                    shopId, request.getShipmentId(), request.getSku());
            return null;
        }

        BigDecimal expected = request.getUnitAmount()
                .multiply(BigDecimal.valueOf(request.getShortageUnits()))
                .setScale(2, RoundingMode.HALF_UP);
        FeeDiscrepancy d = new FeeDiscrepancy();
        d.setShopId(shopId);
        d.setSku(request.getSku());
        d.setShipmentId(request.getShipmentId());
        d.setDiscrepancyType(FeeDiscrepancy.TYPE_INBOUND_SHORTAGE);
        d.setExpectedAmount(expected);
        // 尚未赔付 → 实际发生金额为 0，差额为负（应给未给）
        d.setActualAmount(BigDecimal.ZERO.setScale(2));
        d.setDifference(expected.negate());
        d.setCurrency(request.getCurrency() == null ? "USD" : request.getCurrency());
        d.setEvidence(String.format("货件 %s 入库短收 %d 件 × 单位成本 %s = 应赔 %s；平台尚未赔付%s",
                request.getShipmentId(), request.getShortageUnits(),
                request.getUnitAmount().toPlainString(), expected.toPlainString(),
                request.getNote() == null || request.getNote().isBlank()
                        ? "" : "（举证：" + request.getNote() + "）"));
        d.setStatus(FeeDiscrepancy.STATUS_CANDIDATE);
        d.setDetectedAt(LocalDateTime.now());
        d.setCreateTime(LocalDateTime.now());
        feeDiscrepancyMapper.insert(d);
        log.info("inbound shortage registered shopId={} sku={} shipmentId={} claimable={}",
                shopId, request.getSku(), request.getShipmentId(), expected);
        return d.getId();
    }

    @Override
    public List<FeeDiscrepancy> list(Long shopId, String status, String type) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        LambdaQueryWrapper<FeeDiscrepancy> qw = new LambdaQueryWrapper<FeeDiscrepancy>()
                .eq(FeeDiscrepancy::getShopId, shopId)
                .orderByDesc(FeeDiscrepancy::getId);
        if (status != null && !status.isBlank()) {
            qw.eq(FeeDiscrepancy::getStatus, status.toUpperCase());
        }
        if (type != null && !type.isBlank()) {
            qw.eq(FeeDiscrepancy::getDiscrepancyType, type.toUpperCase());
        }
        return feeDiscrepancyMapper.selectList(qw);
    }

    @Override
    public FeeDiscrepancy get(Long shopId, Long id) {
        if (shopId == null || id == null) {
            throw new IllegalArgumentException("shopId and id must not be null");
        }
        FeeDiscrepancy d = feeDiscrepancyMapper.selectById(id);
        // 归属校验：id 型入参不带 shopId，必须自己判归属，否则改个数字就能读他店数据
        if (d == null || !shopId.equals(d.getShopId())) {
            return null;
        }
        return d;
    }

    @Override
    public boolean updateStatus(Long shopId, Long id, String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        String normalized = status.toUpperCase();
        if (!FeeDiscrepancy.STATUS_CANDIDATE.equals(normalized)
                && !FeeDiscrepancy.STATUS_CLAIMED.equals(normalized)
                && !FeeDiscrepancy.STATUS_REIMBURSED.equals(normalized)
                && !FeeDiscrepancy.STATUS_DISMISSED.equals(normalized)) {
            throw new IllegalArgumentException("unsupported status: " + status);
        }
        FeeDiscrepancy d = get(shopId, id);
        if (d == null) {
            return false;
        }
        d.setStatus(normalized);
        feeDiscrepancyMapper.updateById(d);
        return true;
    }

    private static <T> T requireSuccess(Result<T> result, String what) {
        if (result == null) {
            throw new IllegalStateException(what + "：spapi 无响应");
        }
        if (result.getCode() != 200) {
            throw new IllegalStateException(what + "：" + result.getMessage());
        }
        return result.getData();
    }

    private static List<String> limit(Set<String> values, int max) {
        List<String> list = new ArrayList<>(values);
        return list.size() <= max ? list : list.subList(0, max);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
