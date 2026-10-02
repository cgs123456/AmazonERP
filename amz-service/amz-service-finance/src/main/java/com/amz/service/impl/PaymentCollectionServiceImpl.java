package com.amz.service.impl;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import com.amz.batch.BatchInserts;
import com.amz.dto.PaymentCollectionSummary;
import com.amz.mapper.PaymentCollectionMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.PaymentCollection;
import com.amz.model.SettlementDetail;
import com.amz.parse.SettlementClassifier;
import com.amz.service.PaymentCollectionService;
import com.amz.util.MapArgUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    /** 一次 upsert 语句带多少个订单（每条语句的占位符数量 = 订单数 × 列数）。 */
    @Value("${amz.finance.collection.upsert-batch-size:200}")
    private int upsertBatchSize = 200;

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

        // 幂等交给数据库：不再先把整店台账读进内存建 id 索引，也不逐单 select + insert/update。
        // 短款保留与"有短款则状态仍为 SHORTFALL"两条规则都写在 upsertBatch 的 ODKU 子句里。
        List<PaymentCollection> computed = new ArrayList<>(byOrder.size());
        for (Map.Entry<String, List<SettlementDetail>> entry : byOrder.entrySet()) {
            computed.add(aggregate(entry.getKey(), entry.getValue()));
        }

        BatchInserts.Result batched = BatchInserts.saveChunks(
                computed, upsertBatchSize, this::upsertBatch, this::upsertOne,
                PaymentCollection::getAmazonOrderId, 10);

        if (batched.getFallbackChunks() > 0) {
            // 逐条兜底让结果正确，但"批量这条写路在当前 schema 上走不通"是必须看见的故障
            log.warn("回款台账批量 upsert 有 {} 块退回逐条：shopId={} 原因={}",
                    batched.getFallbackChunks(), shopId, batched.getBatchErrors());
        }
        log.info("rebuild payment collection shopId={} orders={} unattributedSettlementRows={} "
                        + "written={} fallbackChunks={}",
                shopId, computed.size(), unattributed, batched.getInserted(), batched.getFallbackChunks());
        if (batched.getFailed() > 0) {
            // 部分成功必须吵：静默返回"重建完成"会让缺数的台账被当成完整事实用于对账
            throw new IllegalStateException("回款台账重建有 " + batched.getFailed()
                    + " 个订单落库失败（已写入 " + batched.getInserted() + " 个）："
                    + batched.getFailures());
        }
        return computed.size();
    }

    /** 一块一次语句；抛错则由 BatchInserts 只把这一块退回逐条。 */
    private void upsertBatch(List<PaymentCollection> block) {
        paymentCollectionMapper.upsertBatch(block);
    }

    private BatchInserts.RowOutcome upsertOne(PaymentCollection row) {
        try {
            paymentCollectionMapper.upsertBatch(List.of(row));
            return BatchInserts.RowOutcome.INSERTED;
        } catch (Exception e) {
            log.warn("回款台账逐条重写仍失败：shopId={} amazonOrderId={} reason={}",
                    row.getShopId(), row.getAmazonOrderId(), e.getMessage());
            return BatchInserts.RowOutcome.FAILED;
        }
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
            // 归类口径与凭证生成共用 SettlementClassifier，两套「钱」不会各算各的
            switch (SettlementClassifier.classify(d)) {
                case PRINCIPAL -> receivable = receivable.add(amount);
                // 扣费与退款在报表里都是负数，取反后作为正数分项展示
                case FEE -> feeDeducted = feeDeducted.add(amount.negate());
                case REFUND -> refunded = refunded.add(amount.negate());
                case ADJUSTMENT -> reimbursed = reimbursed.add(amount);
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
