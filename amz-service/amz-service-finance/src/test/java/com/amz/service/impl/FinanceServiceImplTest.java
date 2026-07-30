package com.amz.service.impl;

import com.amz.client.KingdeeClient;
import com.amz.finance.CurrencyConverter;
import com.amz.mapper.AccountingVoucherMapper;
import com.amz.model.AccountingVoucher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业财一体化服务实现单元测试（纯 Mockito，不依赖 Spring 容器）。
 * <p>
 * 覆盖：generateOrderVoucher（含币种换算）、calculateProfit（含 4 种 sourceType
 * 按借贷方向加减）、syncToKingdee（含 mock/真实降级/异常/凭证不存在 4 个分支）、listVouchers。
 */
@ExtendWith(MockitoExtension.class)
class FinanceServiceImplTest {

    @Mock
    private AccountingVoucherMapper voucherMapper;

    @Mock
    private CurrencyConverter currencyConverter;

    @Mock
    private KingdeeClient kingdeeClient;

    @InjectMocks
    private FinanceServiceImpl financeService;

    // ---------------- generateOrderVoucher ----------------

    @Test
    @DisplayName("generateOrderVoucher：USD 订单触发币种换算并落库，凭证号 UUID 防并发冲突")
    void generateOrderVoucherUsdWithConversion() {
        Long shopId = 10L;
        String orderNo = "AMZ-ORD-001";
        BigDecimal amount = new BigDecimal("100");
        when(currencyConverter.convertToCny(amount, "USD")).thenReturn(new BigDecimal("725.00"));
        when(currencyConverter.getRate("USD")).thenReturn(new BigDecimal("7.25"));

        AccountingVoucher result = financeService.generateOrderVoucher(shopId, orderNo, amount, "USD");

        ArgumentCaptor<AccountingVoucher> captor = ArgumentCaptor.forClass(AccountingVoucher.class);
        verify(voucherMapper).insert(captor.capture());
        AccountingVoucher persisted = captor.getValue();

        // 凭证号：V + 32 位 hex（UUID 去横线），规避时间戳并发冲突
        assertNotNull(persisted.getVoucherNo());
        assertTrue(persisted.getVoucherNo().matches("V[0-9a-f]{32}"),
                "凭证号应为 V+UUID去横线，实际: " + persisted.getVoucherNo());
        assertEquals(shopId, persisted.getShopId());
        assertEquals(orderNo, persisted.getSourceNo());
        assertEquals("ORDER", persisted.getSourceType());
        assertEquals("PENDING", persisted.getKingdeeSyncStatus());
        assertEquals("USD", persisted.getCurrency());
        assertEquals(new BigDecimal("7.25"), persisted.getExchangeRate());
        assertEquals(new BigDecimal("725.00"), persisted.getCnyAmount());
        assertEquals(amount, persisted.getOriginalAmount());
        // 返回值即落库对象
        assertEquals(persisted.getVoucherNo(), result.getVoucherNo());
    }

    @Test
    @DisplayName("generateOrderVoucher：CNY 订单无换算，原值即本位币")
    void generateOrderVoucherCnyNoConversion() {
        when(currencyConverter.convertToCny(new BigDecimal("200"), "CNY")).thenReturn(new BigDecimal("200.00"));
        when(currencyConverter.getRate("CNY")).thenReturn(BigDecimal.ONE);

        AccountingVoucher v = financeService.generateOrderVoucher(5L, "AMZ-ORD-002", new BigDecimal("200"), "CNY");

        verify(voucherMapper).insert(any(AccountingVoucher.class));
        assertEquals("CNY", v.getCurrency());
        assertEquals(new BigDecimal("200.00"), v.getCnyAmount());
        assertEquals("ORDER", v.getSourceType());
    }

    // ---------------- calculateProfit ----------------

    @Test
    @DisplayName("calculateProfit：ORDER(+) - PROCUREMENT - PLATFORM_FEE - REFUND，未知类型忽略，null 金额按 0")
    void calculateProfitFourSourceTypesByDebitCredit() {
        AccountingVoucher order = voucher("ORDER", new BigDecimal("1000"));
        AccountingVoucher proc = voucher("PROCUREMENT", new BigDecimal("300"));
        AccountingVoucher fee = voucher("PLATFORM_FEE", new BigDecimal("50"));
        AccountingVoucher refund = voucher("REFUND", new BigDecimal("80"));
        AccountingVoucher unknown = voucher("UNKNOWN", new BigDecimal("999"));
        AccountingVoucher orderNullAmt = voucher("ORDER", null); // null 金额按 0
        when(voucherMapper.selectList(any())).thenReturn(
                Arrays.asList(order, proc, fee, refund, unknown, orderNullAmt));

        // 利润 = 1000 - 300 - 50 - 80 + 0 = 570.00
        BigDecimal profit = financeService.calculateProfit(1L, "2026-01-01", "2026-01-31");

        assertEquals(new BigDecimal("570.00"), profit);
    }

    @Test
    @DisplayName("calculateProfit：无凭证时返回 0.00")
    void calculateProfitEmptyReturnsZero() {
        when(voucherMapper.selectList(any())).thenReturn(Collections.emptyList());
        BigDecimal profit = financeService.calculateProfit(1L, null, null);
        assertEquals(new BigDecimal("0.00"), profit);
    }

    @Test
    @DisplayName("calculateProfit：纯订单收入场景（仅 ORDER）")
    void calculateProfitOnlyOrder() {
        when(voucherMapper.selectList(any())).thenReturn(Arrays.asList(
                voucher("ORDER", new BigDecimal("500")),
                voucher("ORDER", new BigDecimal("250.50"))));
        BigDecimal profit = financeService.calculateProfit(1L, null, null);
        assertEquals(new BigDecimal("750.50"), profit);
    }

    // ---------------- syncToKingdee ----------------

    @Test
    @DisplayName("syncToKingdee：mock 客户端返回 KD 号 → 状态 SYNCED，返回 true")
    void syncToKingdeeMockClientReturnsSynced() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(1L);
        v.setVoucherNo("Vabc");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(1L)).thenReturn(v);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KD-1700000000000");

        boolean result = financeService.syncToKingdee(1L);

        assertTrue(result);
        assertEquals("SYNCED", v.getKingdeeSyncStatus());
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：真实客户端降级返回 KINGDEE_MOCK_ 号 → 状态 SYNCING（非 FAILED），返回 true")
    void syncToKingdeeRealClientDegradeReturnsSyncing() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(2L);
        v.setVoucherNo("Vdef");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(2L)).thenReturn(v);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KINGDEE_MOCK_1700000000000");

        boolean result = financeService.syncToKingdee(2L);

        assertTrue(result);
        assertEquals("SYNCING", v.getKingdeeSyncStatus(),
                "真实 API 未对接降级应标记 SYNCING 而非 FAILED");
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：客户端抛异常 → 状态 FAILED，返回 false")
    void syncToKingdeeClientThrowsMarksFailed() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(3L);
        v.setVoucherNo("Vghi");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(3L)).thenReturn(v);
        when(kingdeeClient.syncVoucher(any())).thenThrow(new RuntimeException("金蝶网关超时"));

        boolean result = financeService.syncToKingdee(3L);

        assertFalse(result);
        assertEquals("FAILED", v.getKingdeeSyncStatus());
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：凭证不存在 → 返回 false，不调用 updateById")
    void syncToKingdeeVoucherNotFound() {
        when(voucherMapper.selectById(99L)).thenReturn(null);

        boolean result = financeService.syncToKingdee(99L);

        assertFalse(result);
        verify(voucherMapper, never()).updateById(any(AccountingVoucher.class));
    }

    // ---------------- listVouchers ----------------

    @Test
    @DisplayName("listVouchers：按 shopId 查询并倒序返回")
    void listVouchersByShop() {
        AccountingVoucher v1 = new AccountingVoucher();
        v1.setId(1L);
        AccountingVoucher v2 = new AccountingVoucher();
        v2.setId(2L);
        when(voucherMapper.selectList(any())).thenReturn(Arrays.asList(v2, v1));

        List<AccountingVoucher> list = financeService.listVouchers(10L, null);

        assertEquals(2, list.size());
        verify(voucherMapper).selectList(any());
    }

    @Test
    @DisplayName("listVouchers：指定 sourceType 过滤")
    void listVouchersWithSourceType() {
        when(voucherMapper.selectList(any())).thenReturn(Collections.emptyList());
        List<AccountingVoucher> list = financeService.listVouchers(10L, "ORDER");
        assertTrue(list.isEmpty());
        verify(voucherMapper).selectList(any());
    }

    // ---------------- helpers ----------------

    private AccountingVoucher voucher(String sourceType, BigDecimal cnyAmount) {
        AccountingVoucher v = new AccountingVoucher();
        v.setShopId(1L);
        v.setBizDate("2026-01-15");
        v.setSourceType(sourceType);
        v.setCnyAmount(cnyAmount);
        return v;
    }
}