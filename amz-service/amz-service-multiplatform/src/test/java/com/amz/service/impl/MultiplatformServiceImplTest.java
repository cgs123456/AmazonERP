package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.exception.AttrIsNullException;
import com.amz.finance.PlatformCurrencyConverter;
import com.amz.mapper.UnifiedOrderMapper;
import com.amz.model.UnifiedOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多平台订单聚合服务单元测试（纯 Mockito，不依赖外部平台 API）。
 * <p>
 * 验证平台分发、去重落库、CNY 折算、发货回传、降级容错等核心链路。
 * 去重逻辑已改为批量查询（selectList + in）替代循环内 selectCount，
 * 测试 mock selectList 返回已存在订单集合以验证 N+1 修复。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("多平台订单聚合服务单元测试")
class MultiplatformServiceImplTest {

    @Mock
    private UnifiedOrderMapper unifiedOrderMapper;

    @Mock
    private TemuClient temuClient;

    @Mock
    private TikTokClient tiktokClient;

    @Mock
    private SheinClient sheinClient;

    @Mock
    private PlatformCurrencyConverter currencyConverter;

    @InjectMocks
    private MultiplatformServiceImpl multiplatformService;

    @Test
    @DisplayName("按平台同步 - 未知平台 → 抛出异常")
    void testSyncByPlatformUnknown() {
        assertThrows(AttrIsNullException.class,
                () -> multiplatformService.syncByPlatform(1L, "UNKNOWN"));
    }

    @Test
    @DisplayName("按平台同步 - TEMU 新订单 → 折算 CNY 并落库")
    void testSyncByPlatformTemuNewOrders() {
        UnifiedOrder o1 = buildOrder("TEMU", "TEMU-001", new BigDecimal("10.00"), "USD");
        UnifiedOrder o2 = buildOrder("TEMU", "TEMU-002", new BigDecimal("20.00"), "USD");
        when(temuClient.fetchRecentOrders(1L)).thenReturn(List.of(o1, o2));
        when(unifiedOrderMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(currencyConverter.toCny(new BigDecimal("10.00"), "USD")).thenReturn(new BigDecimal("72.00"));
        when(currencyConverter.toCny(new BigDecimal("20.00"), "USD")).thenReturn(new BigDecimal("144.00"));

        int result = multiplatformService.syncByPlatform(1L, "TEMU");

        assertEquals(2, result, "应插入 2 条新订单");
        verify(unifiedOrderMapper).insert(o1);
        verify(unifiedOrderMapper).insert(o2);
        assertEquals(new BigDecimal("72.00"), o1.getCnyAmount());
        assertEquals(new BigDecimal("144.00"), o2.getCnyAmount());
        assertNotNull(o1.getUnifiedOrderNo());
    }

    @Test
    @DisplayName("按平台同步 - 已存在订单 → 批量去重跳过")
    void testSyncByPlatformDedup() {
        UnifiedOrder o1 = buildOrder("TEMU", "TEMU-001", new BigDecimal("10.00"), "USD");
        UnifiedOrder existing = buildOrder("TEMU", "TEMU-001", new BigDecimal("10.00"), "USD");
        when(temuClient.fetchRecentOrders(1L)).thenReturn(List.of(o1));
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of(existing));

        int result = multiplatformService.syncByPlatform(1L, "TEMU");

        assertEquals(0, result, "已存在订单应跳过");
        verify(unifiedOrderMapper, never()).insert(any(UnifiedOrder.class));
    }

    @Test
    @DisplayName("按平台同步 - 空订单列表 → 返回 0")
    void testSyncByPlatformEmpty() {
        when(temuClient.fetchRecentOrders(1L)).thenReturn(Collections.emptyList());

        int result = multiplatformService.syncByPlatform(1L, "TEMU");

        assertEquals(0, result);
        verify(unifiedOrderMapper, never()).insert(any(UnifiedOrder.class));
    }

    @Test
    @DisplayName("全平台同步 - 单平台异常不影响其他平台（降级返回 0）")
    void testSyncAllPlatformsDegradeOnException() {
        UnifiedOrder tiktokOrder = buildOrder("TIKTOK", "TT-001", new BigDecimal("15.00"), "USD");
        when(temuClient.fetchRecentOrders(1L)).thenThrow(new RuntimeException("Temu API 超时"));
        when(tiktokClient.fetchRecentOrders(1L)).thenReturn(List.of(tiktokOrder));
        when(sheinClient.fetchRecentOrders(1L)).thenReturn(Collections.emptyList());
        when(unifiedOrderMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(currencyConverter.toCny(any(), eq("USD"))).thenReturn(new BigDecimal("108.00"));

        int result = multiplatformService.syncAllPlatforms(1L);

        assertTrue(result >= 1, "Temu 降级为 0，但 TikTok 应同步成功");
        verify(unifiedOrderMapper).insert(tiktokOrder);
    }

    @Test
    @DisplayName("发货回传 - 订单不存在 → 抛出异常")
    void testMarkShippedOrderNotFound() {
        when(unifiedOrderMapper.selectById(99L)).thenReturn(null);

        assertThrows(AttrIsNullException.class,
                () -> multiplatformService.markShipped(99L, "TRK-001"));
    }

    @Test
    @DisplayName("发货回传 - TEMU 订单 → 调用 temuClient.markShipped 并更新状态")
    void testMarkShippedTemuSuccess() {
        UnifiedOrder order = buildOrder("TEMU", "TEMU-001", BigDecimal.ZERO, "CNY");
        order.setId(1L);
        when(unifiedOrderMapper.selectById(1L)).thenReturn(order);
        when(temuClient.markShipped("TEMU-001", "TRK-001")).thenReturn(true);

        boolean result = multiplatformService.markShipped(1L, "TRK-001");

        assertTrue(result);
        assertEquals("SHIPPED", order.getStatus());
        assertEquals("TRK-001", order.getTrackingNo());
        verify(unifiedOrderMapper).updateById(order);
    }

    @Test
    @DisplayName("发货回传 - 平台返回 false → 不更新订单状态")
    void testMarkShippedPlatformReturnsFalse() {
        UnifiedOrder order = buildOrder("SHEIN", "SHEIN-001", BigDecimal.ZERO, "CNY");
        order.setId(1L);
        when(unifiedOrderMapper.selectById(1L)).thenReturn(order);
        when(sheinClient.markShipped("SHEIN-001", "TRK-002")).thenReturn(false);

        boolean result = multiplatformService.markShipped(1L, "TRK-002");

        assertEquals(false, result);
        verify(unifiedOrderMapper, never()).updateById(any(UnifiedOrder.class));
    }

    private UnifiedOrder buildOrder(String platform, String platformOrderNo,
                                    BigDecimal originalAmount, String currency) {
        UnifiedOrder o = new UnifiedOrder();
        o.setShopId(1L);
        o.setPlatform(platform);
        o.setPlatformOrderNo(platformOrderNo);
        o.setOriginalAmount(originalAmount);
        o.setCurrency(currency);
        return o;
    }

    private static <T> T assertNotNull(T value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
        return value;
    }
}