package com.amz.voucher;

import com.amz.client.ProcurementCostClient;
import com.amz.client.dto.RemotePurchaseOrder;
import com.amz.dto.ProcurementVoucherReport;
import com.amz.exception.AttrIsNullException;
import com.amz.finance.CurrencyConverter;
import com.amz.mapper.AccountingVoucherMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.AccountingVoucher;
import com.amz.result.PageMeta;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.amz.service.impl.FinanceServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;
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
 * 采购单 → PROCUREMENT 凭证 producer 测试（跨采购域读取）。
 * <p>
 * 要守住的三条：
 * (1) 方向：采购成本落「借库存商品 / 贷应付账款」，calculateProfit 对它做减法；
 * (2) 降级诚实：采购域读不到时 remoteDegraded=true，绝不返回「0 张凭证」当成功，
 *     否则「没读到」会被下游读成「这批采购没花钱」；
 * (3) 幂等：同 orderNo 只一张凭证；缺 orderNo 的行跳过而不是每次都新记一张。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("采购单凭证 producer（跨服务）")
class ProcurementVoucherProducerTest {

    @Mock
    private AccountingVoucherMapper voucherMapper;

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @Mock
    private CurrencyConverter currencyConverter;

    @Mock
    private ProcurementCostClient procurementCostClient;

    @InjectMocks
    private FinanceServiceImpl financeService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AccountingVoucher.class);
    }

    @BeforeEach
    void stubCny() {
        when(currencyConverter.getRate("CNY")).thenReturn(BigDecimal.ONE);
        when(currencyConverter.convertToCny(any(), eq("CNY")))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private RemotePurchaseOrder order(String orderNo, String sku, Integer qty, String unitPrice, String total) {
        RemotePurchaseOrder o = new RemotePurchaseOrder();
        o.setShopId(7L);
        o.setOrderNo(orderNo);
        o.setSku(sku);
        o.setQuantity(qty);
        o.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        o.setTotalAmount(total == null ? null : new BigDecimal(total));
        o.setStatus("QC_PASSED");
        return o;
    }

    private Result<List<RemotePurchaseOrder>> ok(List<RemotePurchaseOrder> rows, boolean hasMore, String nextCursor) {
        Result<List<RemotePurchaseOrder>> r = Result.success(rows);
        PageMeta meta = new PageMeta();
        meta.setSize(200);
        meta.setReturned(rows.size());
        meta.setHasMore(hasMore);
        meta.setTruncated(hasMore);
        meta.setNextCursor(nextCursor);
        r.setPage(meta);
        return r;
    }

    @Test
    @DisplayName("缺 shopId 抛错")
    void nullShopThrows() {
        assertThrows(AttrIsNullException.class, () -> financeService.generateProcurementVouchers(null));
    }

    @Test
    @DisplayName("成本可确认的采购单 → PROCUREMENT 凭证：借库存商品 / 贷应付账款，按 CNY 记账")
    void generatesCostVoucher() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenReturn(ok(Collections.singletonList(order("PO-1001", "SKU-1", 10, "35.00", "350.00")), false, null));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertEquals(1, report.getGenerated());
        assertFalse(report.isRemoteDegraded());
        ArgumentCaptor<AccountingVoucher> captor = ArgumentCaptor.forClass(AccountingVoucher.class);
        verify(voucherMapper).insert(captor.capture());
        AccountingVoucher v = captor.getValue();
        assertEquals("PROCUREMENT", v.getSourceType());
        assertEquals("PO-1001", v.getSourceNo());
        assertEquals("1405", v.getDebitAccount());
        assertEquals("2202", v.getCreditAccount());
        assertEquals("CNY", v.getCurrency());
        assertEquals(0, new BigDecimal("350.00").compareTo(v.getOriginalAmount()));
    }

    @Test
    @DisplayName("总额缺失时按单价×数量补齐；两者都缺则跳过并计数")
    void amountFallsBackToUnitPriceTimesQuantity() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenReturn(ok(Arrays.asList(
                        order("PO-2001", "SKU-2", 4, "12.50", null),
                        order("PO-2002", "SKU-3", null, null, null)), false, null));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertEquals(1, report.getGenerated());
        assertEquals(1, report.getSkippedZeroAmount());
        ArgumentCaptor<AccountingVoucher> captor = ArgumentCaptor.forClass(AccountingVoucher.class);
        verify(voucherMapper).insert(captor.capture());
        assertEquals(0, new BigDecimal("50.00").compareTo(captor.getValue().getOriginalAmount()));
    }

    @Test
    @DisplayName("缺单据号没有幂等键：跳过并计数，不出凭证")
    void blankOrderNoIsSkipped() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenReturn(ok(Collections.singletonList(order("  ", "SKU-4", 1, "9.00", "9.00")), false, null));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertEquals(1, report.getSkippedNoOrderNo());
        verify(voucherMapper, never()).insert(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("同 orderNo 第二次跑命中幂等，不产生第二张凭证")
    void secondRunIsIdempotent() {
        List<RemotePurchaseOrder> rows = Collections.singletonList(order("PO-3001", "SKU-5", 2, "5.00", "10.00"));
        when(procurementCostClient.listVoucherSources(any(), any(), any())).thenReturn(ok(rows, false, null));
        financeService.generateProcurementVouchers(7L);

        AccountingVoucher persisted = new AccountingVoucher();
        persisted.setSourceNo("PO-3001");
        persisted.setSourceType("PROCUREMENT");
        when(voucherMapper.selectOne(any())).thenReturn(persisted);
        ProcurementVoucherReport second = financeService.generateProcurementVouchers(7L);

        assertEquals(0, second.getGenerated());
        assertEquals(1, second.getExisting());
        verify(voucherMapper, times(1)).insert(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("采购域返回失败：标记降级且一张凭证都不出（不能把读不到当成没成本）")
    void degradedResultStopsWithoutFabricating() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenReturn(Result.failure("procurement service degraded: connection refused"));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertTrue(report.isRemoteDegraded());
        assertTrue(report.getRemoteMessage().contains("connection refused"));
        assertEquals(0, report.getGenerated());
        verify(voucherMapper, never()).insert(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("客户端抛异常（未走降级工厂）同样归为降级，异常信息保留")
    void clientExceptionIsDegradedToo() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenThrow(new IllegalStateException("feign 超时"));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertTrue(report.isRemoteDegraded());
        assertTrue(report.getRemoteMessage().contains("feign 超时"));
    }

    @Test
    @DisplayName("按 nextCursor 翻页读到尽为止，两页都入账")
    void followsCursorUntilExhausted() {
        when(procurementCostClient.listVoucherSources(eq(7L), any(), eq(null)))
                .thenReturn(ok(Collections.singletonList(order("PO-4001", "SKU-6", 1, "8.00", "8.00")), true, "v1:100"));
        when(procurementCostClient.listVoucherSources(eq(7L), any(), eq("v1:100")))
                .thenReturn(ok(Collections.singletonList(order("PO-4002", "SKU-7", 1, "9.00", "9.00")), false, null));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertEquals(2, report.getPagesRead());
        assertEquals(2, report.getGenerated());
        assertFalse(report.isCapped());
        verify(procurementCostClient).listVoucherSources(7L, 200, "v1:100");
    }

    @Test
    @DisplayName("对端一直说还有下一页：达到最大页数时 capped=true，不谎称读完")
    void pageCapIsReported() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenReturn(ok(Collections.singletonList(order("PO-5001", "SKU-8", 1, "7.00", "7.00")), true, "v1:1"));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertTrue(report.isCapped());
        assertEquals(50, report.getPagesRead());
    }

    @Test
    @DisplayName("空页：scanned=0、未降级也未截断（真的没有成本可确认的单）")
    void emptySourceIsNotDegraded() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenReturn(ok(Collections.emptyList(), false, null));

        ProcurementVoucherReport report = financeService.generateProcurementVouchers(7L);

        assertEquals(0, report.getScanned());
        assertFalse(report.isRemoteDegraded());
        assertFalse(report.isCapped());
    }

    @Test
    @DisplayName("凭证汇率与折算都按 CNY 取，不出现凭空假币种的换算")
    void usesCnyCurrencyOnly() {
        when(procurementCostClient.listVoucherSources(any(), any(), any()))
                .thenReturn(ok(Collections.singletonList(order("PO-6001", "SKU-9", 3, "20.00", "60.00")), false, null));

        financeService.generateProcurementVouchers(7L);

        verify(currencyConverter, times(1)).convertToCny(any(), eq("CNY"));
        verify(currencyConverter, times(1)).getRate("CNY");
    }
}
