package com.amz.service.impl;

import com.amz.dto.PaymentCollectionSummary;
import com.amz.mapper.PaymentCollectionMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.PaymentCollection;
import com.amz.model.SettlementDetail;
import com.amz.service.PaymentCollectionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单级回款台账实现（T05）。
 * <p>
 * 金额方向约定（与平台一致）：应收为正，费用与退款存正数量级便于阅读，
 * 实收 = 应收 - 费用 - 退款 + 赔付净额 —— 该等式恒成立，任何一笔改口径都会破坏它。
 * <p>
 * 短款（shortfall）不由本服务推算，而由费用比对（T06）写入：
 * 没有费用预估基准时，「少给了多少」这个数就不存在，
 * 此处宁可留 null 也不填 0 —— 0 会被读成「没有短款」这个结论。
 */
@Slf4j
@Service
public class PaymentCollectionServiceImpl implements PaymentCollectionService {

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private PaymentCollectionMapper paymentCollectionMapper;

    @Override
    public int rebuild(Long shopId) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        List<SettlementDetail> details = settlementDetailMapper.selectList(
                new LambdaQueryWrapper<SettlementDetail>().eq(SettlementDetail::getShopId, shopId));
        if (details == null || details.isEmpty()) {
            log.info("rebuild payment collection: no settlement rows shopId={}", shopId);
            return 0;
        }

        Map<String, List<SettlementDetail>> byOrder = new LinkedHashMap<>();
        int unattributed = 0;
        for (SettlementDetail d : details) {
            if (d.getOrderId() == null || d.getOrderId().isBlank()) {
                // 平台调整行（Adjustment）常无订单号，无法归属到订单级台账
                unattributed++;
                continue;
            }
            byOrder.computeIfAbsent(d.getOrderId(), k -> new ArrayList<>()).add(d);
        }

        Map<String, PaymentCollection> existing = new LinkedHashMap<>();
        for (PaymentCollection pc : paymentCollectionMapper.selectList(
                new LambdaQueryWrapper<PaymentCollection>().eq(PaymentCollection::getShopId, shopId))) {
            existing.put(pc.getOrderId(), pc);
        }

        int count = 0;
        for (Map.Entry<String, List<SettlementDetail>> entry : byOrder.entrySet()) {
            PaymentCollection computed = aggregate(entry.getKey(), entry.getValue());
            PaymentCollection old = existing.get(entry.getKey());
            if (old == null) {
                paymentCollectionMapper.insert(computed);
            } else {
                // 保留既有短款（由费用比对写入），避免重算把它清零
                computed.setId(old.getId());
                computed.setShortfall(old.getShortfall());
                applyStatusByShortfall(computed);
                paymentCollectionMapper.updateById(computed);
            }
            count++;
        }
        log.info("rebuild payment collection shopId={} orders={} unattributedSettlementRows={}",
                shopId, count, unattributed);
        return count;
    }

    @Override
    public List<PaymentCollection> list(Long shopId, String status) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        LambdaQueryWrapper<PaymentCollection> qw = new LambdaQueryWrapper<PaymentCollection>()
                .eq(PaymentCollection::getShopId, shopId)
                .orderByDesc(PaymentCollection::getId);
        if (status != null && !status.isBlank()) {
            qw.eq(PaymentCollection::getStatus, status.toUpperCase());
        }
        return paymentCollectionMapper.selectList(qw);
    }

    @Override
    public PaymentCollectionSummary summary(Long shopId) {
        List<PaymentCollection> rows = list(shopId, null);
        PaymentCollectionSummary summary = new PaymentCollectionSummary();
        summary.setShopId(shopId);
        summary.setTotalOrders(rows.size());

        Set<String> currencies = new LinkedHashSet<>();
        boolean shortfallCalculated = false;
        for (PaymentCollection row : rows) {
            BigDecimal net = nz(row.getNetReceived());
            summary.setReceivableTotal(summary.getReceivableTotal().add(nz(row.getReceivable())));
            summary.setNetReceivedTotal(summary.getNetReceivedTotal().add(net));
            if (row.getCurrency() != null && !row.getCurrency().isBlank()) {
                currencies.add(row.getCurrency());
            }
            if (row.getShortfall() != null) {
                shortfallCalculated = true;
            }
            String status = row.getStatus() == null ? "" : row.getStatus();
            switch (status) {
                case PaymentCollection.STATUS_PENDING:
                    summary.setPendingOrders(summary.getPendingOrders() + 1);
                    break;
                case PaymentCollection.STATUS_IN_TRANSIT:
                    summary.setInTransitOrders(summary.getInTransitOrders() + 1);
                    // 在途未回：已结算但尚未到账的钱
                    summary.setInTransitAmount(summary.getInTransitAmount().add(net));
                    break;
                case PaymentCollection.STATUS_SETTLED:
                    summary.setSettledOrders(summary.getSettledOrders() + 1);
                    summary.setSettledAmount(summary.getSettledAmount().add(net));
                    break;
                case PaymentCollection.STATUS_REFUNDED:
                    summary.setRefundedOrders(summary.getRefundedOrders() + 1);
                    break;
                case PaymentCollection.STATUS_SHORTFALL:
                    summary.setShortfallOrders(summary.getShortfallOrders() + 1);
                    summary.setShortfallAmount(summary.getShortfallAmount().add(nz(row.getShortfall())));
                    break;
                default:
                    break;
            }
        }
        summary.setCurrencies(currencies);
        if (currencies.size() > 1) {
            summary.addWarning("涉及多币种（" + String.join(", ", currencies)
                    + "），合计金额不可跨币种相加，请按币种分别解读");
        }
        if (!shortfallCalculated) {
            summary.addWarning("短款金额为 0 不代表没有短款 —— 当前尚未完成费用比对（预估费用 vs 实际扣费），"
                    + "该项数据不可用");
        }
        if (summary.getPendingOrders() == 0) {
            summary.addWarning("台账由结算数据聚合而成，无结算记录的订单不会出现在此表中，"
                    + "因此「未回」订单无法枚举（需接入订单域数据）");
        }
        return summary;
    }

    @Override
    public boolean applyShortfall(Long shopId, String orderId, BigDecimal shortfall) {
        if (shopId == null || orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("shopId and orderId must not be null");
        }
        PaymentCollection row = paymentCollectionMapper.selectOne(
                new LambdaQueryWrapper<PaymentCollection>()
                        .eq(PaymentCollection::getShopId, shopId)
                        .eq(PaymentCollection::getOrderId, orderId)
                        .last("LIMIT 1"));
        if (row == null) {
            log.info("applyShortfall: no payment collection row shopId={} orderId={}", shopId, orderId);
            return false;
        }
        row.setShortfall(shortfall);
        applyStatusByShortfall(row);
        row.setLastCalculatedAt(LocalDateTime.now());
        paymentCollectionMapper.updateById(row);
        return true;
    }

    /**
     * 由结算明细聚合单个订单。
     * <p>
     * 实收直接取有符号金额总和（而非各分项相减）—— 保证与平台「净影响」严格一致，
     * 分项相减一旦漏掉某个交易类型就会凭空产生差额。
     */
    private PaymentCollection aggregate(String orderId, List<SettlementDetail> rows) {
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal feeDeducted = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal reimbursed = BigDecimal.ZERO;
        BigDecimal netReceived = BigDecimal.ZERO;
        String currency = null;
        String depositDate = null;

        for (SettlementDetail d : rows) {
            BigDecimal amount = nz(d.getAmount());
            netReceived = netReceived.add(amount);
            String type = d.getTransactionType() == null ? "" : d.getTransactionType();
            if ("Order".equalsIgnoreCase(type)) {
                if ("Principal".equalsIgnoreCase(d.getAmountType())) {
                    receivable = receivable.add(amount);
                } else {
                    // Order 类型下的非 Principal 行（佣金、配送费等）为平台扣费
                    feeDeducted = feeDeducted.add(amount.negate());
                }
            } else if ("Refund".equalsIgnoreCase(type)) {
                refunded = refunded.add(amount.negate());
            } else if ("Adjustment".equalsIgnoreCase(type)) {
                reimbursed = reimbursed.add(amount);
            } else {
                // ServiceFee / 其他类型：按扣费处理（负数为扣，正数为返还）
                feeDeducted = feeDeducted.add(amount.negate());
            }
            if (currency == null && d.getCurrency() != null && !d.getCurrency().isBlank()) {
                currency = d.getCurrency();
            }
            if (d.getDepositDate() != null && !d.getDepositDate().isBlank()
                    && (depositDate == null || d.getDepositDate().compareTo(depositDate) > 0)) {
                depositDate = d.getDepositDate();
            }
        }

        PaymentCollection pc = new PaymentCollection();
        pc.setShopId(rows.get(0).getShopId());
        pc.setOrderId(orderId);
        pc.setCurrency(currency);
        pc.setReceivable(scale(receivable));
        pc.setFeeDeducted(scale(feeDeducted));
        pc.setRefunded(scale(refunded));
        pc.setReimbursed(scale(reimbursed));
        pc.setNetReceived(scale(netReceived));
        pc.setDepositDate(depositDate);
        pc.setLastCalculatedAt(LocalDateTime.now());
        pc.setStatus(resolveBaseStatus(receivable, refunded, depositDate));
        return pc;
    }

    /**
     * 基础状态（不考虑短款）：已退 > 已回 > 在途。
     */
    private static String resolveBaseStatus(BigDecimal receivable, BigDecimal refunded, String depositDate) {
        if (receivable.signum() > 0 && refunded.compareTo(receivable) >= 0) {
            return PaymentCollection.STATUS_REFUNDED;
        }
        return isDepositReached(depositDate)
                ? PaymentCollection.STATUS_SETTLED
                : PaymentCollection.STATUS_IN_TRANSIT;
    }

    /**
     * 短款优先：有短款的订单需要动作，若被「已回」掩盖就永远不会被处理。
     */
    private static void applyStatusByShortfall(PaymentCollection pc) {
        if (pc.getShortfall() != null && pc.getShortfall().signum() > 0) {
            pc.setStatus(PaymentCollection.STATUS_SHORTFALL);
        } else if (PaymentCollection.STATUS_SHORTFALL.equals(pc.getStatus())) {
            pc.setStatus(resolveBaseStatus(nz(pc.getReceivable()), nz(pc.getRefunded()), pc.getDepositDate()));
        }
    }

    /**
     * 存款日是否已到（<= 今天）。缺失或无法解析一律视为「未到账」——
     * 把无法判断的情况当成已回，会让在途资金凭空消失。
     */
    private static boolean isDepositReached(String depositDate) {
        if (depositDate == null || depositDate.isBlank()) {
            return false;
        }
        try {
            return !OffsetDateTime.parse(depositDate).toLocalDate().isAfter(LocalDate.now());
        } catch (Exception ignored) {
            // 退回「取前 10 位当日期」的宽松解析（结算文件里存在 2026-09-08 这种纯日期形式）
        }
        try {
            return !LocalDate.parse(depositDate.trim().substring(0, 10)).isAfter(LocalDate.now());
        } catch (Exception e) {
            return false;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
