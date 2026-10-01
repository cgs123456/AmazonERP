package com.amz.service.impl;

import com.amz.dto.PaymentCollectionSummary;
import com.amz.mapper.PaymentCollectionMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.PaymentCollection;
import com.amz.model.SettlementDetail;
import com.amz.result.PageRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单级回款台账单元测试（纯 Mockito）。
 * <p>
 * 重点验证：
 * <ol>
 *   <li><b>恒等式</b>：实收 = 应收 - 费用 - 退款 + 赔付，且实收取自「有符号金额总和」，两者必须一致</li>
 *   <li><b>状态判定</b>：已退 > 有差额 > 已回 > 在途；无法解析的存款日一律按「未到账」</li>
 *   <li><b>短款语义</b>：未做费用比对时为 null（不是 0）；重算不清零已写入的短款</li>
 *   <li><b>概览口径</b>：在途未回金额与已回短款金额分别累计，不互抵</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PaymentCollectionServiceImplTest {

    private static final Long SHOP_ID = 1L;
    /** 远期存款日 → 在途。 */
    private static final String DEPOSIT_FUTURE = "2099-12-31T00:00:00Z";
    /** 历史存款日 → 已回。 */
    private static final String DEPOSIT_PAST = "2000-01-01T00:00:00Z";

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @Mock
    private PaymentCollectionMapper paymentCollectionMapper;

    @InjectMocks
    private PaymentCollectionServiceImpl paymentCollectionService;

    private static SettlementDetail detail(String amazonOrderId, String txType, String amountType,
                                           String amount, String depositDate) {
        SettlementDetail d = new SettlementDetail();
        d.setShopId(SHOP_ID);
        d.setAmazonOrderId(amazonOrderId);
        d.setSku("SKU-" + (amazonOrderId == null ? "NA" : amazonOrderId));
        d.setTransactionType(txType);
        d.setAmountType(amountType);
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("USD");
        d.setDepositDate(depositDate);
        d.setRowKey("key-" + amazonOrderId + "-" + amountType + "-" + amount);
        return d;
    }

    private List<SettlementDetail> fourOrderSettlement() {
        List<SettlementDetail> rows = new ArrayList<>();
        // ORD-1 正常回款（已回）
        rows.add(detail("ORD-1", "Order", "Principal", "29.99", DEPOSIT_PAST));
        rows.add(detail("ORD-1", "Order", "Commission", "-4.50", DEPOSIT_PAST));
        rows.add(detail("ORD-1", "Order", "FBAPerUnitFulfillmentFee", "-5.03", DEPOSIT_PAST));
        // ORD-2 已结算未到账（在途）
        rows.add(detail("ORD-2", "Order", "Principal", "49.99", DEPOSIT_FUTURE));
        rows.add(detail("ORD-2", "Order", "Commission", "-7.50", DEPOSIT_FUTURE));
        // ORD-3 全额退款（已退）
        rows.add(detail("ORD-3", "Order", "Principal", "19.99", DEPOSIT_PAST));
        rows.add(detail("ORD-3", "Refund", "Principal", "-19.99", DEPOSIT_PAST));
        // ORD-4 正常回款（后续由费用比对打入短款）
        rows.add(detail("ORD-4", "Order", "Principal", "15.00", DEPOSIT_PAST));
        rows.add(detail("ORD-4", "Order", "Commission", "-2.25", DEPOSIT_PAST));
        // 平台调整为无订单号行 → 无法归属订单级台账
        rows.add(detail(null, "Adjustment", "FBA Inventory Reimbursement", "12.50", DEPOSIT_PAST));
        return rows;
    }

    @Test
    @DisplayName("重算：4 个订单聚合正确，恒等式成立，状态分类符合优先级")
    void rebuildAggregatesOrders() {
        when(settlementDetailMapper.selectList(any())).thenReturn(fourOrderSettlement());
        when(paymentCollectionMapper.upsertBatch(anyList())).thenReturn(4);

        int orders = paymentCollectionService.rebuild(SHOP_ID);
        assertEquals(4, orders, "无订单号的调整行不应产生订单台账");

        List<PaymentCollection> saved = captureUpserted();

        PaymentCollection ord1 = find(saved, "ORD-1");
        assertEquals(new BigDecimal("29.99"), ord1.getReceivable());
        assertEquals(new BigDecimal("9.53"), ord1.getFeeDeducted());
        assertEquals(new BigDecimal("20.46"), ord1.getNetReceived());
        assertEquals(PaymentCollection.STATUS_SETTLED, ord1.getStatus());
        assertNull(ord1.getShortfall(), "未做费用比对时短款必须为 null，不能是 0");

        PaymentCollection ord2 = find(saved, "ORD-2");
        assertEquals(new BigDecimal("42.49"), ord2.getNetReceived());
        assertEquals(PaymentCollection.STATUS_IN_TRANSIT, ord2.getStatus());

        PaymentCollection ord3 = find(saved, "ORD-3");
        assertEquals(new BigDecimal("19.99"), ord3.getRefunded());
        assertEquals(BigDecimal.ZERO.setScale(2), ord3.getNetReceived());
        assertEquals(PaymentCollection.STATUS_REFUNDED, ord3.getStatus());

        PaymentCollection ord4 = find(saved, "ORD-4");
        assertEquals(new BigDecimal("12.75"), ord4.getNetReceived());
        assertEquals(PaymentCollection.STATUS_SETTLED, ord4.getStatus());

        // 恒等式校验：实收必须是「有符号金额总和」，分项相减也必须得到同一个数
        for (PaymentCollection pc : saved) {
            BigDecimal byItems = pc.getReceivable().subtract(pc.getFeeDeducted())
                    .subtract(pc.getRefunded()).add(pc.getReimbursed());
            assertEquals(0, byItems.compareTo(pc.getNetReceived()),
                    "恒等式被破坏：" + pc.getAmazonOrderId() + " " + byItems + " != " + pc.getNetReceived());
        }
    }

    @Test
    @DisplayName("重算不再预读整店台账：幂等交给 upsertBatch 那一条语句")
    void rebuildDoesNotPreloadLedger() {
        when(settlementDetailMapper.selectList(any())).thenReturn(fourOrderSettlement());
        when(paymentCollectionMapper.upsertBatch(anyList())).thenReturn(4);

        assertEquals(4, paymentCollectionService.rebuild(SHOP_ID));

        // 旧实现把整店台账全部读进内存建 id 索引，再逐单 select + insert/updateById
        verify(paymentCollectionMapper, never()).selectList(any());
        verify(paymentCollectionMapper, never()).insert(any(PaymentCollection.class));
        verify(paymentCollectionMapper, never()).updateById(any(PaymentCollection.class));
    }

    @Test
    @DisplayName("重算必须遍历结算明细的全部页，不能只处理前 500 行")
    void rebuildTraversesAllSettlementPages() {
        List<SettlementDetail> firstDetailPage = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            SettlementDetail row = detail("ORD-1", "Order", "Principal", "10.00", DEPOSIT_PAST);
            row.setId(1000L - i);
            firstDetailPage.add(row);
        }
        // 第二页必须把第一页"探边界多读的那一行"（id=500）也带回来：
        // keyset 分页靠 cursor=id<501 把它留给下一页，替真实 DB 少给一行就等于测试自己把数据弄丢
        SettlementDetail carriedOverRow = detail("ORD-1", "Order", "Principal", "10.00", DEPOSIT_PAST);
        carriedOverRow.setId(500L);
        SettlementDetail secondPageDetail = detail("ORD-2", "Order", "Principal", "20.00", DEPOSIT_PAST);
        secondPageDetail.setId(499L);

        when(settlementDetailMapper.selectList(any()))
                .thenReturn(firstDetailPage, new ArrayList<>(List.of(carriedOverRow, secondPageDetail)));
        when(paymentCollectionMapper.upsertBatch(anyList())).thenReturn(2);

        int orders = paymentCollectionService.rebuild(SHOP_ID);

        assertEquals(2, orders);
        List<PaymentCollection> saved = captureUpserted();
        PaymentCollection secondPageOrder = find(saved, "ORD-2");
        assertEquals(0, new BigDecimal("20.00").compareTo(secondPageOrder.getReceivable()),
                "第二页的订单也必须进台账，否则整页被静默丢掉；实际=" + secondPageOrder.getReceivable());
        assertEquals(0, new BigDecimal("5010.00").compareTo(find(saved, "ORD-1").getReceivable()),
                "第一页 501 行同订单：被分页截断后应收应为前 500 行之和；实际="
                        + find(saved, "ORD-1").getReceivable());
        verify(settlementDetailMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("450 个订单 / 块 200 → 恰好 3 条 upsert 语句")
    void rebuildUpsertsInChunks() {
        List<SettlementDetail> details = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            details.add(detail("ORD-" + i, "Order", "Principal", "10.00", DEPOSIT_PAST));
        }
        when(settlementDetailMapper.selectList(any())).thenReturn(details);
        List<Integer> blockSizes = new ArrayList<>();
        when(paymentCollectionMapper.upsertBatch(anyList())).thenAnswer(invocation -> {
            List<PaymentCollection> block = invocation.getArgument(0);
            blockSizes.add(block.size());
            return block.size();
        });

        assertEquals(450, paymentCollectionService.rebuild(SHOP_ID));
        assertEquals(List.of(200, 200, 50), blockSizes);
    }

    @Test
    @DisplayName("某块 upsert 失败：该块退回逐单，全部成功则不报错")
    void failingChunkFallsBackToPerOrder() {
        List<SettlementDetail> details = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            details.add(detail("ORD-" + i, "Order", "Principal", "10.00", DEPOSIT_PAST));
        }
        when(settlementDetailMapper.selectList(any())).thenReturn(details);
        List<Integer> shapes = new ArrayList<>();
        when(paymentCollectionMapper.upsertBatch(anyList())).thenAnswer(invocation -> {
            List<PaymentCollection> block = invocation.getArgument(0);
            shapes.add(block.size());
            if (block.size() > 1) {
                throw new RuntimeException("deadlock found");
            }
            return 1;
        });
        ReflectionTestUtils.setField(paymentCollectionService, "upsertBatchSize", 2);

        assertEquals(3, paymentCollectionService.rebuild(SHOP_ID));
        // 块 1（2 单）批量失败 → 退回逐单 2 次；块 2 只有 1 单 → 直接逐单 1 次
        assertEquals(List.of(2, 1, 1, 1), shapes, "先看到块的形状，再谈退回是否发生");
        verify(paymentCollectionMapper, times(4)).upsertBatch(anyList());
    }

    @Test
    @DisplayName("逐单仍失败：抛错并带上成功/失败计数，不静默返回「重建完成」")
    void partialFailureIsLoud() {
        List<SettlementDetail> details = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            details.add(detail("ORD-" + i, "Order", "Principal", "10.00", DEPOSIT_PAST));
        }
        when(settlementDetailMapper.selectList(any())).thenReturn(details);
        when(paymentCollectionMapper.upsertBatch(anyList())).thenAnswer(invocation -> {
            List<PaymentCollection> block = invocation.getArgument(0);
            if (block.stream().anyMatch(r -> "ORD-1".equals(r.getAmazonOrderId()))) {
                throw new RuntimeException("data too long for column");
            }
            return 1;
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentCollectionService.rebuild(SHOP_ID));
        assertTrue(ex.getMessage().contains("1 个订单落库失败"), ex.getMessage());
        assertTrue(ex.getMessage().contains("已写入 1 个"), ex.getMessage());
    }

    @Test
    @DisplayName("重算：无结算数据时返回 0 且不写库")
    void rebuildWithoutSettlementRows() {
        when(settlementDetailMapper.selectList(any())).thenReturn(new ArrayList<>());
        assertEquals(0, paymentCollectionService.rebuild(SHOP_ID));
        verify(paymentCollectionMapper, times(0)).insert(any(PaymentCollection.class));
    }

    @Test
    @DisplayName("存款日解析：纯日期形式可用；无法解析按「未到账」处理")
    void depositDateParsing() {
        List<SettlementDetail> rows = new ArrayList<>();
        rows.add(detail("ORD-PLAIN", "Order", "Principal", "10.00", "2000-01-01"));
        rows.add(detail("ORD-BROKEN", "Order", "Principal", "10.00", "not-a-date"));
        rows.add(detail("ORD-MISSING", "Order", "Principal", "10.00", null));
        when(settlementDetailMapper.selectList(any())).thenReturn(rows);
        when(paymentCollectionMapper.upsertBatch(anyList())).thenReturn(3);

        paymentCollectionService.rebuild(SHOP_ID);

        List<PaymentCollection> saved = captureUpserted();
        assertEquals(PaymentCollection.STATUS_SETTLED, find(saved, "ORD-PLAIN").getStatus());
        assertEquals(PaymentCollection.STATUS_IN_TRANSIT, find(saved, "ORD-BROKEN").getStatus(),
                "无法解析的存款日不能当作已回账");
        assertEquals(PaymentCollection.STATUS_IN_TRANSIT, find(saved, "ORD-MISSING").getStatus());
    }

    /** 取出所有经由批量 upsert 写出的行（分块后不再是一行一次调用）。 */
    @SuppressWarnings("unchecked")
    private List<PaymentCollection> captureUpserted() {
        ArgumentCaptor<List<PaymentCollection>> captor = ArgumentCaptor.forClass(List.class);
        verify(paymentCollectionMapper, atLeastOnce()).upsertBatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).collect(Collectors.toList());
    }

    @Test
    @DisplayName("applyShortfall：写入短款并置为「有差额」；订单台账不存在时返回 false")
    void applyShortfallBehaviour() {
        PaymentCollection row = new PaymentCollection();
        row.setId(7L);
        row.setShopId(SHOP_ID);
        row.setAmazonOrderId("ORD-1");
        row.setReceivable(new BigDecimal("29.99"));
        row.setRefunded(BigDecimal.ZERO);
        row.setNetReceived(new BigDecimal("20.46"));
        row.setDepositDate(DEPOSIT_PAST);
        row.setStatus(PaymentCollection.STATUS_SETTLED);
        when(paymentCollectionMapper.selectOne(any())).thenReturn(row);
        when(paymentCollectionMapper.updateById(any(PaymentCollection.class))).thenReturn(1);

        assertTrue(paymentCollectionService.applyShortfall(SHOP_ID, "ORD-1", new BigDecimal("1.35")));
        assertEquals(PaymentCollection.STATUS_SHORTFALL, row.getStatus());
        assertEquals(new BigDecimal("1.35"), row.getShortfall());
        assertNotNull(row.getLastCalculatedAt());

        when(paymentCollectionMapper.selectOne(any())).thenReturn(null);
        assertFalse(paymentCollectionService.applyShortfall(SHOP_ID, "ORD-404", new BigDecimal("1.00")));

        assertThrows(IllegalArgumentException.class,
                () -> paymentCollectionService.applyShortfall(SHOP_ID, "  ", BigDecimal.ONE));
    }

    @Test
    @DisplayName("applyShortfall：抹平差额（0）后状态回退为「已回」")
    void applyShortfallZeroResetsStatus() {
        PaymentCollection row = new PaymentCollection();
        row.setId(8L);
        row.setShopId(SHOP_ID);
        row.setAmazonOrderId("ORD-1");
        row.setReceivable(new BigDecimal("29.99"));
        row.setRefunded(BigDecimal.ZERO);
        row.setNetReceived(new BigDecimal("20.46"));
        row.setDepositDate(DEPOSIT_PAST);
        row.setStatus(PaymentCollection.STATUS_SHORTFALL);
        row.setShortfall(new BigDecimal("1.35"));
        when(paymentCollectionMapper.selectOne(any())).thenReturn(row);
        when(paymentCollectionMapper.updateById(any(PaymentCollection.class))).thenReturn(1);

        paymentCollectionService.applyShortfall(SHOP_ID, "ORD-1", BigDecimal.ZERO);

        assertEquals(PaymentCollection.STATUS_SETTLED, row.getStatus(),
                "短款归零后不应继续挂在「有差额」状态");
    }

    @Test
    @DisplayName("概览：在途未回与已回短款分别累计，不互相抵消")
    void summaryKeepsTwoAmountsSeparate() {
        // 分组行取自 MySQL 8.4 对同一份 fixture 的真实聚合结果
        // （见 docs/superpowers/evidence/2026-10-01-code-review-round2-high-medium-low.md §3）
        when(paymentCollectionMapper.aggregateSummaryByCurrency(SHOP_ID)).thenReturn(List.of(
                group("USD", 7, "131.97", "83.70", 1, 1, "42.49", 2, "20.46", 1, 2, "1.10", 2),
                group("EUR", 1, "19.99", "15.00", 0, 0, "0.00", 1, "15.00", 0, 0, "0.00", 0),
                group(null, 1, "5.00", "4.00", 0, 0, "0.00", 1, "4.00", 0, 0, "0.00", 0)));

        PaymentCollectionSummary summary = paymentCollectionService.summary(SHOP_ID);

        assertEquals(9, summary.getTotalOrders());
        assertEquals(4, summary.getSettledOrders());
        assertEquals(1, summary.getInTransitOrders());
        assertEquals(1, summary.getRefundedOrders());
        assertEquals(2, summary.getShortfallOrders());
        assertEquals(1, summary.getPendingOrders());

        assertEquals(0, new BigDecimal("42.49").compareTo(summary.getInTransitAmount()),
                "在途未回 = 在途订单实收");
        assertEquals(0, new BigDecimal("39.46").compareTo(summary.getSettledAmount()));
        assertEquals(0, new BigDecimal("1.10").compareTo(summary.getShortfallAmount()),
                "已回短款不能被在途金额抵消");
        assertEquals(0, new BigDecimal("156.96").compareTo(summary.getReceivableTotal()));
        assertEquals(0, new BigDecimal("102.70").compareTo(summary.getNetReceivedTotal()));
        assertEquals(2, summary.getCurrencies().size());
        assertTrue(summary.getCurrencies().contains("USD"));
        assertTrue(summary.getCurrencies().contains("EUR"),
                "无币种分组只参与合计，不能当成一个币种列示：" + summary.getCurrencies());
        // 概览不得再把整店台账读进内存
        verify(paymentCollectionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("概览：无短款数据来源时显式警告（0 不等于没有短款）")
    void summaryWarnsWhenShortfallUnknown() {
        when(paymentCollectionMapper.aggregateSummaryByCurrency(SHOP_ID)).thenReturn(List.of(
                group("USD", 1, "29.99", "20.46", 0, 0, "0.00", 1, "20.46", 0, 0, "0.00", 0)));

        PaymentCollectionSummary summary = paymentCollectionService.summary(SHOP_ID);

        assertTrue(summary.getWarnings().stream().anyMatch(w -> w.contains("短款")),
                summary.getWarnings().toString());
        assertTrue(summary.getWarnings().stream().anyMatch(w -> w.contains("未回")),
                "应提示「未回」订单无法枚举的口径限制：" + summary.getWarnings());
    }

    @Test
    @DisplayName("概览：多币种时给出不可跨币种相加的提示，单币种不给")
    void summaryMultiCurrencyWarning() {
        when(paymentCollectionMapper.aggregateSummaryByCurrency(SHOP_ID)).thenReturn(List.of(
                group("USD", 1, "29.99", "20.46", 0, 0, "0.00", 1, "20.46", 0, 0, "0.00", 1),
                group("EUR", 1, "19.99", "15.00", 0, 0, "0.00", 1, "15.00", 0, 0, "0.00", 0)));

        PaymentCollectionSummary multi = paymentCollectionService.summary(SHOP_ID);
        assertEquals(2, multi.getCurrencies().size());
        assertTrue(multi.getWarnings().stream().anyMatch(w -> w.contains("多币种")),
                multi.getWarnings().toString());

        when(paymentCollectionMapper.aggregateSummaryByCurrency(SHOP_ID)).thenReturn(List.of(
                group("USD", 1, "29.99", "20.46", 0, 0, "0.00", 1, "20.46", 0, 0, "0.00", 1)));
        PaymentCollectionSummary single = paymentCollectionService.summary(SHOP_ID);
        assertFalse(single.getWarnings().stream().anyMatch(w -> w.contains("多币种")),
                "单币种不应出现多币种提示：" + single.getWarnings());
    }

    @Test
    @DisplayName("概览：无台账行时全为 0 且仍给出两条口径警告")
    void summaryWithoutRows() {
        when(paymentCollectionMapper.aggregateSummaryByCurrency(SHOP_ID)).thenReturn(List.of());

        PaymentCollectionSummary summary = paymentCollectionService.summary(SHOP_ID);

        assertEquals(0, summary.getTotalOrders());
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getNetReceivedTotal()));
        assertTrue(summary.getCurrencies().isEmpty());
        assertEquals(2, summary.getWarnings().size(), summary.getWarnings().toString());
    }

    @Test
    @DisplayName("list：状态过滤大写化，shopId 为空抛错")
    void listFiltersStatus() {
        when(paymentCollectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        assertTrue(paymentCollectionService.list(SHOP_ID, "settled", PageRequest.first(50)).items().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> paymentCollectionService.list(null, null, PageRequest.first(50)));
    }

    private static PaymentCollection pc(String amazonOrderId, String status, String receivable,
                                        String netReceived, String shortfall) {
        PaymentCollection pc = new PaymentCollection();
        pc.setShopId(SHOP_ID);
        pc.setAmazonOrderId(amazonOrderId);
        pc.setCurrency("USD");
        pc.setReceivable(new BigDecimal(receivable));
        pc.setFeeDeducted(BigDecimal.ZERO);
        pc.setRefunded(BigDecimal.ZERO);
        pc.setReimbursed(BigDecimal.ZERO);
        pc.setNetReceived(new BigDecimal(netReceived));
        pc.setStatus(status);
        if (shortfall != null) {
            pc.setShortfall(new BigDecimal(shortfall));
        }
        return pc;
    }

    /**
     * 构造一行「按币种聚合」的返回：列名与别名与 PaymentCollectionMapper#aggregateSummaryByCurrency 一致，
     * 金额用 BigDecimal、计数用 Long —— 与 MySQL 8.4 驱动实际回传的类型一致（SUM(DECIMAL) 是 DECIMAL，
     * COUNT 是 BIGINT，SUM(CASE...1...0) 也是 DECIMAL）。
     */
    private static Map<String, Object> group(String currency, long orderCount, String receivable,
                                             String netReceived, int pending, int inTransit,
                                             String inTransitAmount, int settled, String settledAmount,
                                             int refunded, int shortfallOrders, String shortfallAmount,
                                             long shortfallKnownRows) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("currency", currency);
        row.put("orderCount", orderCount);
        row.put("receivableTotal", new BigDecimal(receivable));
        row.put("netReceivedTotal", new BigDecimal(netReceived));
        row.put("pendingOrders", decimalCount(pending));
        row.put("inTransitOrders", decimalCount(inTransit));
        row.put("inTransitAmount", new BigDecimal(inTransitAmount));
        row.put("settledOrders", decimalCount(settled));
        row.put("settledAmount", new BigDecimal(settledAmount));
        row.put("refundedOrders", decimalCount(refunded));
        row.put("shortfallOrders", decimalCount(shortfallOrders));
        row.put("shortfallAmount", new BigDecimal(shortfallAmount));
        row.put("shortfallKnownRows", decimalCount((int) shortfallKnownRows));
        return row;
    }

    /** SUM(CASE ... THEN 1 ELSE 0 END) 在 MySQL 里返回 DECIMAL，不是整数。 */
    private static BigDecimal decimalCount(int value) {
        return new BigDecimal(value);
    }

    private static PaymentCollection find(List<PaymentCollection> rows, String amazonOrderId) {
        return rows.stream().filter(r -> amazonOrderId.equals(r.getAmazonOrderId())).findFirst()
                .orElseThrow(() -> new AssertionError("order not aggregated: " + amazonOrderId));
    }
}
