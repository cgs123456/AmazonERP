package com.amz.voucher;

import com.amz.dto.SettlementVoucherReport;
import com.amz.exception.AttrIsNullException;
import com.amz.finance.CurrencyConverter;
import com.amz.mapper.AccountingVoucherMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.AccountingVoucher;
import com.amz.model.SettlementDetail;
import com.amz.service.impl.FinanceServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算行 → PLATFORM_FEE / REFUND 凭证的 producer 测试。
 * <p>
 * 动因：{@code calculateProfit} 早就在扣这两类成本，但没有任何生产者写入，
 * 利润只剩收入侧、系统性偏高。这里锁三件事：
 * (1) 方向正确——扣项（负数）落成「正数成本」凭证，被利润减掉；
 * (2) 幂等——同一 rowKey 重复调用不产生第二张凭证；
 * (3) 不造数——无币种/零金额/归属其它链路的行只计数，不按 1:1 汇率硬编。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("结算行凭证 producer")
class SettlementVoucherProducerTest {

    @Mock
    private AccountingVoucherMapper voucherMapper;

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @Mock
    private CurrencyConverter currencyConverter;

    @InjectMocks
    private FinanceServiceImpl financeService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AccountingVoucher.class);
        TableInfoHelper.initTableInfo(assistant, SettlementDetail.class);
    }

    @BeforeEach
    void stubConversion() {
        when(currencyConverter.getRate(anyString())).thenReturn(new BigDecimal("7.20"));
        when(currencyConverter.convertToCny(any(), anyString()))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("7.20")));
    }

    private SettlementDetail row(long id, String txType, String amountType, String amount, String currency, String rowKey) {
        SettlementDetail d = new SettlementDetail();
        d.setId(id);
        d.setShopId(7L);
        d.setAmazonOrderId("114-1-1");
        d.setTransactionType(txType);
        d.setAmountType(amountType);
        d.setAmount(amount == null ? null : new BigDecimal(amount));
        d.setCurrency(currency);
        d.setRowKey(rowKey);
        return d;
    }

    private void onePage(List<SettlementDetail> rows) {
        when(settlementDetailMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);
    }

    @Test
    @DisplayName("缺 shopId 直接抛错，不返回空报告")
    void nullShopIdThrows() {
        assertThrows(AttrIsNullException.class, () -> financeService.generateSettlementVouchers(null));
    }

    @Test
    @DisplayName("Order 非 Principal 行 → PLATFORM_FEE 凭证，金额取反成正数成本，借贷方向按科目表")
    void feeRowGeneratesPositiveCostVoucher() {
        onePage(Collections.singletonList(
                row(1, "Order", "Commission", "-3.50", "USD", "rk-fee-1")));

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(1, report.getFeeVouchers());
        ArgumentCaptor<AccountingVoucher> captor = ArgumentCaptor.forClass(AccountingVoucher.class);
        verify(voucherMapper).insert(captor.capture());
        AccountingVoucher v = captor.getValue();
        assertEquals("PLATFORM_FEE", v.getSourceType());
        assertEquals(new BigDecimal("3.50"), v.getOriginalAmount());
        // calculateProfit 对 PLATFORM_FEE 做减法，因此这里必须是正数成本
        // （折算值按数值比较：乘法的 scale 是两端 scale 之和，不该被当成契约）
        assertEquals(0, new BigDecimal("25.2").compareTo(v.getCnyAmount()));
        assertEquals("6601", v.getDebitAccount());
        assertEquals("1002", v.getCreditAccount());
        assertEquals("rk-fee-1", v.getSourceNo());
        assertEquals("PENDING", v.getKingdeeSyncStatus());
    }

    @Test
    @DisplayName("Refund 行 → REFUND 凭证（借主营收入红冲 / 贷应收）")
    void refundRowGeneratesRefundVoucher() {
        onePage(Collections.singletonList(
                row(2, "Refund", "Refund", "-20.00", "USD", "rk-ref-2")));

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(1, report.getRefundVouchers());
        ArgumentCaptor<AccountingVoucher> captor = ArgumentCaptor.forClass(AccountingVoucher.class);
        verify(voucherMapper).insert(captor.capture());
        AccountingVoucher v = captor.getValue();
        assertEquals("REFUND", v.getSourceType());
        assertEquals(new BigDecimal("20.00"), v.getOriginalAmount());
        assertEquals("6001", v.getDebitAccount());
        assertEquals("1122", v.getCreditAccount());
    }

    @Test
    @DisplayName("Order/Principal 与 Adjustment 不出凭证：收入走订单凭证、调整走索赔链路")
    void principalAndAdjustmentAreSkipped() {
        onePage(java.util.Arrays.asList(
                row(3, "Order", "Principal", "100.00", "USD", "rk-p-3"),
                row(4, "Adjustment", "Adjustment", "5.00", "USD", "rk-a-4")));

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(2, report.getSkippedByKind());
        assertEquals(0, report.getFeeVouchers() + report.getRefundVouchers());
        verify(voucherMapper, never()).insert(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("无币种的行跳过并计数，不按 1:1 汇率硬编")
    void missingCurrencyIsSkippedNotAssumed() {
        onePage(Collections.singletonList(row(5, "ServiceFee", null, "-8.00", "  ", "rk-5")));

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(1, report.getSkippedNoCurrency());
        verify(voucherMapper, never()).insert(any(AccountingVoucher.class));
        verify(currencyConverter, never()).convertToCny(any(), anyString());
    }

    @Test
    @DisplayName("零金额行不出凭证，避免污染利润")
    void zeroAmountIsSkipped() {
        onePage(Collections.singletonList(row(6, "ServiceFee", null, "0.00", "USD", "rk-6")));

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(1, report.getSkippedZeroAmount());
        verify(voucherMapper, never()).insert(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("同 rowKey 第二次调用命中幂等，不产生第二张凭证")
    void secondRunIsIdempotent() {
        List<SettlementDetail> rows = Collections.singletonList(
                row(7, "Order", "FulfillmentFee", "-1.20", "USD", "rk-7"));
        onePage(rows);
        SettlementVoucherReport first = financeService.generateSettlementVouchers(7L);
        assertEquals(1, first.getFeeVouchers());

        // 模拟已落库：同源凭证查得到
        onePage(rows);
        when(voucherMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existing("rk-7", "PLATFORM_FEE"));
        SettlementVoucherReport second = financeService.generateSettlementVouchers(7L);

        assertEquals(0, second.getFeeVouchers());
        assertEquals(1, second.getExisting());
        verify(voucherMapper, times(1)).insert(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("缺 rowKey 的历史行退到行 PK 作幂等键，仍不重复入账")
    void rowKeyMissingFallsBackToRowPk() {
        onePage(Collections.singletonList(row(8, "ServiceFee", null, "-2.00", "USD", null)));

        financeService.generateSettlementVouchers(7L);

        ArgumentCaptor<AccountingVoucher> captor = ArgumentCaptor.forClass(AccountingVoucher.class);
        verify(voucherMapper).insert(captor.capture());
        assertEquals("SD-8", captor.getValue().getSourceNo());
    }

    @Test
    @DisplayName("命中扫描上限时 capped=true 并告警，不能把「扫到上限」说成「全部处理完」")
    void scanCapIsReported() {
        when(settlementDetailMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer((org.mockito.stubbing.Answer<List<SettlementDetail>>) inv -> fullPage(inv));

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(5000, report.getScanned());
        assertTrue(report.isCapped());
        // 5000 / 500 = 10 页
        verify(settlementDetailMapper, times(10)).selectList(any(LambdaQueryWrapper.class));
    }

    /** 造一整页（500 行、id 递增、每行都是扣费），用来把扫描逻辑推到上限。 */
    private List<SettlementDetail> fullPage(InvocationOnMock inv) {
        List<SettlementDetail> page = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            long id = ++lastId;
            page.add(row(id, "Order", "Commission", "-1.00", "USD", "rk-" + id));
        }
        return page;
    }

    private long lastId = 0L;

    @Test
    @DisplayName("结算行为空：报告扫描 0 行且未截断（真的没数据，与扫到上限区分开）")
    void emptySettlementIsNotCapped() {
        onePage(Collections.emptyList());

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(0, report.getScanned());
        assertFalse(report.isCapped());
        verify(voucherMapper, never()).insert(any(AccountingVoucher.class));
    }

    private AccountingVoucher existing(String sourceNo, String sourceType) {
        AccountingVoucher v = new AccountingVoucher();
        v.setShopId(7L);
        v.setSourceNo(sourceNo);
        v.setSourceType(sourceType);
        return v;
    }

    @Test
    @DisplayName("汇总金额只累加真正生成的凭证原币")
    void amountSumCoversOnlyGenerated() {
        onePage(java.util.Arrays.asList(
                row(9, "Order", "Commission", "-3.00", "USD", "rk-9"),
                row(10, "Refund", "Refund", "-4.00", "USD", "rk-10"),
                row(11, "Order", "Principal", "99.00", "USD", "rk-11")));

        SettlementVoucherReport report = financeService.generateSettlementVouchers(7L);

        assertEquals(0, new BigDecimal("7.00").compareTo(report.getOriginalAmountSum()));
        verify(voucherMapper, times(2)).insert(any(AccountingVoucher.class));
    }
}
