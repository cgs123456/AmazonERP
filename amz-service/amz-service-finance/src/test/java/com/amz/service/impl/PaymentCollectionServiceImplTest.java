package com.amz.service.impl;

import com.amz.dto.PaymentCollectionSummary;
import com.amz.mapper.PaymentCollectionMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.PaymentCollection;
import com.amz.model.SettlementDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private static SettlementDetail detail(String orderId, String txType, String amountType,
                                           String amount, String depositDate) {
        SettlementDetail d = new SettlementDetail();
        d.setShopId(SHOP_ID);
        d.setOrderId(orderId);
        d.setSku("SKU-" + (orderId == null ? "NA" : orderId));
        d.setTransactionType(txType);
        d.setAmountType(amountType);
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("USD");
        d.setDepositDate(depositDate);
        d.setRowKey("key-" + orderId + "-" + amountType + "-" + amount);
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
        when(paymentCollectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(paymentCollectionMapper.insert(any(PaymentCollection.class))).thenReturn(1);

        int orders = paymentCollectionService.rebuild(SHOP_ID);
        assertEquals(4, orders, "无订单号的调整行不应产生订单台账");

        ArgumentCaptor<PaymentCollection> captor = ArgumentCaptor.forClass(PaymentCollection.class);
        verify(paymentCollectionMapper, times(4)).insert(captor.capture());
        List<PaymentCollection> saved = captor.getAllValues();

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
                    "恒等式被破坏：" + pc.getOrderId() + " " + byItems + " != " + pc.getNetReceived());
        }
    }

    @Test
    @DisplayName("重算：保留既有短款并让状态回到「有差额」（不清零）")
    void rebuildPreservesExistingShortfall() {
        when(settlementDetailMapper.selectList(any())).thenReturn(fourOrderSettlement());
        PaymentCollection existing = new PaymentCollection();
        existing.setId(99L);
        existing.setShopId(SHOP_ID);
        existing.setOrderId("ORD-4");
        existing.setShortfall(new BigDecimal("1.10"));
        existing.setStatus(PaymentCollection.STATUS_SHORTFALL);
        when(paymentCollectionMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(paymentCollectionMapper.updateById(any(PaymentCollection.class))).thenReturn(1);

        paymentCollectionService.rebuild(SHOP_ID);

        ArgumentCaptor<PaymentCollection> captor = ArgumentCaptor.forClass(PaymentCollection.class);
        verify(paymentCollectionMapper).updateById(captor.capture());
        PaymentCollection updated = captor.getValue();
        assertEquals(99L, updated.getId(), "更新必须复用既有主键");
        assertEquals(new BigDecimal("1.10"), updated.getShortfall());
        assertEquals(PaymentCollection.STATUS_SHORTFALL, updated.getStatus());
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
        when(paymentCollectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(paymentCollectionMapper.insert(any(PaymentCollection.class))).thenReturn(1);

        paymentCollectionService.rebuild(SHOP_ID);

        ArgumentCaptor<PaymentCollection> captor = ArgumentCaptor.forClass(PaymentCollection.class);
        verify(paymentCollectionMapper, times(3)).insert(captor.capture());
        List<PaymentCollection> saved = captor.getAllValues();
        assertEquals(PaymentCollection.STATUS_SETTLED, find(saved, "ORD-PLAIN").getStatus());
        assertEquals(PaymentCollection.STATUS_IN_TRANSIT, find(saved, "ORD-BROKEN").getStatus(),
                "无法解析的存款日不能当作已回账");
        assertEquals(PaymentCollection.STATUS_IN_TRANSIT, find(saved, "ORD-MISSING").getStatus());
    }

    @Test
    @DisplayName("applyShortfall：写入短款并置为「有差额」；订单台账不存在时返回 false")
    void applyShortfallBehaviour() {
        PaymentCollection row = new PaymentCollection();
        row.setId(7L);
        row.setShopId(SHOP_ID);
        row.setOrderId("ORD-1");
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
        row.setOrderId("ORD-1");
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
        List<PaymentCollection> rows = new ArrayList<>();
        rows.add(pc("ORD-1", PaymentCollection.STATUS_SETTLED, "29.99", "20.46", null));
        rows.add(pc("ORD-2", PaymentCollection.STATUS_IN_TRANSIT, "49.99", "42.49", null));
        rows.add(pc("ORD-3", PaymentCollection.STATUS_REFUNDED, "19.99", "0.00", null));
        rows.add(pc("ORD-4", PaymentCollection.STATUS_SHORTFALL, "15.00", "12.75", "1.10"));
        when(paymentCollectionMapper.selectList(any())).thenReturn(rows);

        PaymentCollectionSummary summary = paymentCollectionService.summary(SHOP_ID);

        assertEquals(4, summary.getTotalOrders());
        assertEquals(1, summary.getSettledOrders());
        assertEquals(1, summary.getInTransitOrders());
        assertEquals(1, summary.getRefundedOrders());
        assertEquals(1, summary.getShortfallOrders());
        assertEquals(0, summary.getPendingOrders());

        assertEquals(0, new BigDecimal("42.49").compareTo(summary.getInTransitAmount()),
                "在途未回 = 在途订单实收");
        assertEquals(0, new BigDecimal("20.46").compareTo(summary.getSettledAmount()));
        assertEquals(0, new BigDecimal("1.10").compareTo(summary.getShortfallAmount()),
                "已回短款不能被在途金额抵消");
        assertEquals(1, summary.getCurrencies().size());
        assertTrue(summary.getCurrencies().contains("USD"));
    }

    @Test
    @DisplayName("概览：无短款数据来源时显式警告（0 不等于没有短款）")
    void summaryWarnsWhenShortfallUnknown() {
        List<PaymentCollection> rows = new ArrayList<>();
        rows.add(pc("ORD-1", PaymentCollection.STATUS_SETTLED, "29.99", "20.46", null));
        when(paymentCollectionMapper.selectList(any())).thenReturn(rows);

        PaymentCollectionSummary summary = paymentCollectionService.summary(SHOP_ID);

        assertTrue(summary.getWarnings().stream().anyMatch(w -> w.contains("短款")),
                summary.getWarnings().toString());
        assertTrue(summary.getWarnings().stream().anyMatch(w -> w.contains("未回")),
                "应提示「未回」订单无法枚举的口径限制：" + summary.getWarnings());
    }

    @Test
    @DisplayName("概览：多币种时给出不可跨币种相加的提示")
    void summaryMultiCurrencyWarning() {
        List<PaymentCollection> rows = new ArrayList<>();
        rows.add(pc("ORD-1", PaymentCollection.STATUS_SETTLED, "29.99", "20.46", null));
        PaymentCollection eur = pc("ORD-2", PaymentCollection.STATUS_SETTLED, "19.99", "15.00", null);
        eur.setCurrency("EUR");
        rows.add(eur);
        when(paymentCollectionMapper.selectList(any())).thenReturn(rows);

        PaymentCollectionSummary summary = paymentCollectionService.summary(SHOP_ID);

        assertEquals(2, summary.getCurrencies().size());
        assertTrue(summary.getWarnings().stream().anyMatch(w -> w.contains("多币种")),
                summary.getWarnings().toString());
    }

    @Test
    @DisplayName("list：状态过滤大写化，shopId 为空抛错")
    void listFiltersStatus() {
        when(paymentCollectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        assertTrue(paymentCollectionService.list(SHOP_ID, "settled").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> paymentCollectionService.list(null, null));
    }

    private static PaymentCollection pc(String orderId, String status, String receivable,
                                        String netReceived, String shortfall) {
        PaymentCollection pc = new PaymentCollection();
        pc.setShopId(SHOP_ID);
        pc.setOrderId(orderId);
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

    private static PaymentCollection find(List<PaymentCollection> rows, String orderId) {
        return rows.stream().filter(r -> orderId.equals(r.getOrderId())).findFirst()
                .orElseThrow(() -> new AssertionError("order not aggregated: " + orderId));
    }
}
