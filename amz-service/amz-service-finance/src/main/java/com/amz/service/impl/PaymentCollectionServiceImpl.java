package com.amz.service.impl;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import com.amz.dto.PaymentCollectionSummary;
import com.amz.mapper.PaymentCollectionMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.PaymentCollection;
import com.amz.model.SettlementDetail;
import com.amz.service.PaymentCollectionService;
import com.amz.util.MapArgUtils;
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
        List<SettlementDetail> details = loadAllSettlementDetails(shopId);
        if (details == null || details.isEmpty()) {
            log.info("rebuild payment collection: no settlement rows shopId={}", shopId);
            return 0;
        }

        Map<String, List<SettlementDetail>> byOrder = new LinkedHashMap<>();
        int unattributed = 0;
        for (SettlementDetail d : details) {
            if (d.getAmazonOrderId() == null || d.getAmazonOrderId().isBlank()) {
                // 平台调整行（Adjustment）常无订单号，无法归属到订单级台账
                unattributed++;
                continue;
            }
            byOrder.computeIfAbsent(d.getAmazonOrderId(), k -> new ArrayList<>()).add(d);
        }

        Map<String, PaymentCollection> existing = new LinkedHashMap<>();
        for (PaymentCollection pc : loadAllPaymentCollections(shopId)) {
            existing.put(pc.getAmazonOrderId(), pc);
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
    public PageResult<PaymentCollection> list(Long shopId, String status, PageRequest page) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<PaymentCollection> qw = new LambdaQueryWrapper<PaymentCollection>()
                .eq(PaymentCollection::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            qw.eq(PaymentCollection::getStatus, status.toUpperCase());
        }
        Long cursorId = req.cursorId();
        if (cursorId != null) {
            qw.lt(PaymentCollection::getId, cursorId);
        }
        qw.orderByDesc(PaymentCollection::getId)
                .last("LIMIT " + req.probeSize());
        List<PaymentCollection> rows = paymentCollectionMapper.selectList(qw);
        if (rows.size() > req.size()) {
            log.warn("回款台账列表被截断：shopId={} size={}，调用方需携带 nextCursor 继续翻页",
                    shopId, req.size());
        }
        return PageResult.of(rows, req.size(), row -> PageRequest.encodeCursor(row.getId()));
    }

    @Override
    public PaymentCollectionSummary summary(Long shopId) {
        // 聚合下沉到 SQL：只取回「每个币种一行」，不再把整店台账物化进内存。
        // 口径见 PaymentCollectionMapper#aggregateSummaryByCurrency；状态字面量与 STATUS_* 常量
        // 的一致性由 PaymentCollectionSummarySqlContractTest 锁住。
        List<Map<String, Object>> groups = paymentCollectionMapper.aggregateSummaryByCurrency(shopId);
        PaymentCollectionSummary summary = new PaymentCollectionSummary();
        summary.setShopId(shopId);

        Set<String> currencies = new LinkedHashSet<>();
        long shortfallKnownRows = 0;
        for (Map<String, Object> group : groups) {
            if (group == null) {
                continue;
            }
            String currency = MapArgUtils.toStr(group, "currency");
            if (currency != null && !currency.isBlank()) {
                currencies.add(currency);
            }
            summary.setTotalOrders(summary.getTotalOrders() + MapArgUtils.toInt(group, "orderCount", 0));
            summary.setPendingOrders(summary.getPendingOrders() + MapArgUtils.toInt(group, "pendingOrders", 0));
            summary.setInTransitOrders(
                    summary.getInTransitOrders() + MapArgUtils.toInt(group, "inTransitOrders", 0));
            summary.setSettledOrders(summary.getSettledOrders() + MapArgUtils.toInt(group, "settledOrders", 0));
            summary.setRefundedOrders(
                    summary.getRefundedOrders() + MapArgUtils.toInt(group, "refundedOrders", 0));
            summary.setShortfallOrders(
                    summary.getShortfallOrders() + MapArgUtils.toInt(group, "shortfallOrders", 0));
            summary.setReceivableTotal(summary.getReceivableTotal()
                    .add(MapArgUtils.toBigDecimal(group, "receivableTotal", BigDecimal.ZERO)));
            summary.setNetReceivedTotal(summary.getNetReceivedTotal()
                    .add(MapArgUtils.toBigDecimal(group, "netReceivedTotal", BigDecimal.ZERO)));
            summary.setInTransitAmount(summary.getInTransitAmount()
                    .add(MapArgUtils.toBigDecimal(group, "inTransitAmount", BigDecimal.ZERO)));
            summary.setSettledAmount(summary.getSettledAmount()
                    .add(MapArgUtils.toBigDecimal(group, "settledAmount", BigDecimal.ZERO)));
            summary.setShortfallAmount(summary.getShortfallAmount()
                    .add(MapArgUtils.toBigDecimal(group, "shortfallAmount", BigDecimal.ZERO)));
            Long knownRows = MapArgUtils.toLong(group, "shortfallKnownRows", 0L);
            shortfallKnownRows += knownRows;
        }
        summary.setCurrencies(currencies);
        boolean shortfallCalculated = shortfallKnownRows > 0;
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

    /**
     * 内部汇总必须遍历全部页，不能把第一页误当成全量财务事实。
     * 仍以固定批次读取，避免单次 selectList 随历史数据无限增长。
     */
    private List<PaymentCollection> loadAllPaymentCollections(Long shopId) {
        List<PaymentCollection> all = new ArrayList<>();
        PageRequest page = PageRequest.first(PageRequest.MAX_SIZE);
        while (true) {
            LambdaQueryWrapper<PaymentCollection> qw = new LambdaQueryWrapper<PaymentCollection>()
                    .eq(PaymentCollection::getShopId, shopId);
            Long cursorId = page.cursorId();
            if (cursorId != null) {
                qw.lt(PaymentCollection::getId, cursorId);
            }
            qw.orderByDesc(PaymentCollection::getId)
                    .last("LIMIT " + page.probeSize());
            List<PaymentCollection> rows = paymentCollectionMapper.selectList(qw);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            int visible = Math.min(rows.size(), page.size());
            all.addAll(rows.subList(0, visible));
            if (rows.size() <= page.size()) {
                break;
            }
            page = PageRequest.of(page.size(),
                    PageRequest.encodeCursor(rows.get(visible - 1).getId()));
        }
        return all;
    }

    private List<SettlementDetail> loadAllSettlementDetails(Long shopId) {
        List<SettlementDetail> all = new ArrayList<>();
        PageRequest page = PageRequest.first(PageRequest.MAX_SIZE);
        while (true) {
            LambdaQueryWrapper<SettlementDetail> qw = new LambdaQueryWrapper<SettlementDetail>()
                    .eq(SettlementDetail::getShopId, shopId);
            Long cursorId = page.cursorId();
            if (cursorId != null) {
                qw.lt(SettlementDetail::getId, cursorId);
            }
            qw.orderByDesc(SettlementDetail::getId)
                    .last("LIMIT " + page.probeSize());
            List<SettlementDetail> rows = settlementDetailMapper.selectList(qw);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            int visible = Math.min(rows.size(), page.size());
            all.addAll(rows.subList(0, visible));
            if (rows.size() <= page.size()) {
                break;
            }
            page = PageRequest.of(page.size(),
                    PageRequest.encodeCursor(rows.get(visible - 1).getId()));
        }
        return all;
    }
    @Override
    public boolean applyShortfall(Long shopId, String amazonOrderId, BigDecimal shortfall) {
        if (shopId == null || amazonOrderId == null || amazonOrderId.isBlank()) {
            throw new IllegalArgumentException("shopId and amazonOrderId must not be null");
        }
        PaymentCollection row = paymentCollectionMapper.selectOne(
                new LambdaQueryWrapper<PaymentCollection>()
                        .eq(PaymentCollection::getShopId, shopId)
                        .eq(PaymentCollection::getAmazonOrderId, amazonOrderId)
                        .last("LIMIT 1"));
        if (row == null) {
            log.info("applyShortfall: no payment collection row shopId={} amazonOrderId={}", shopId, amazonOrderId);
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
    private PaymentCollection aggregate(String amazonOrderId, List<SettlementDetail> rows) {
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
        pc.setAmazonOrderId(amazonOrderId);
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
