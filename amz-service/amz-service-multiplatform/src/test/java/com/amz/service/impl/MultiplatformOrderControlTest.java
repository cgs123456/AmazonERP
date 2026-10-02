package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.finance.PlatformCurrencyConverter;
import com.amz.mapper.UnifiedOrderMapper;
import com.amz.model.UnifiedOrder;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 多平台订单的控制面契约：分页、发货归属、同步汇总。
 *
 * <p>三件事都必须写在测试里，因为它们的错误形态都是「看起来正常」：
 * <ul>
 *   <li>列表以前没有 LIMIT，店铺订单量上来后一次请求会把整张
 *       {@code amz_unified_order} 读进内存；空数组和「被截断」在响应里长得一样。</li>
 *   <li>发货原来是 {@code IllegalStateException}（越权被全局兜底成 500），并且用 lenient 判定：
 *       该判定只在「没有 userId」的上下文里才会因为缺授权列表而放行（定时任务要走那条分支），
 *       所以改严格是纵深防御；对外真正可复现的修复是越权要变成读得懂的业务拒绝、
 *       运单号必填，以及「上下文根本没建起来」时也不能发货。</li>
 *   <li>{@code syncAllPlatforms} 把每个平台的异常吞成返回值 0，端点又只回一个 int，
 *       于是「三个平台全挂」和「确实没有新单」在页面上是同一个数字。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("多平台订单分页与发货归属契约")
class MultiplatformOrderControlTest {

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
    private MultiplatformServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        // 断言 wrapper 生成的 SQL 前必须先把实体注册进 MyBatis-Plus 的 lambda 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UnifiedOrder.class);
    }

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("订单列表按 id 做 keyset 分页，并按 size+1 探测下一页")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listOrdersUsesKeysetPaging() {
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of(
                order(50L, 1L, "TEMU", "T-1"), order(49L, 1L, "SHEIN", "S-1"), order(48L, 1L, "TEMU", "T-2")));

        PageResult<UnifiedOrder> page = service.listOrders(1L, PageRequest.of(2, null));

        assertEquals(2, page.items().size());
        assertTrue(page.hasMore(), "第 3 行是探测行，说明仍有下一页");
        assertEquals(PageRequest.encodeCursor(49L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(unifiedOrderMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("shop_id ="), "列表没有按店铺过滤：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("翻页游标非法时报业务错误，不是 500")
    void listOrdersRejectsBadCursor() {
        // 必须是合法编码、非法载荷：否则异常在 PageRequest.of 构造时就抛了，测不到服务侧的解析
        PageRequest bad = PageRequest.of(2, PageRequest.encodeCursor("not-a-number"));

        assertThrows(InvalidParamException.class, () -> service.listOrders(1L, bad));
        verifyNoInteractions(unifiedOrderMapper);
    }

    @Test
    @DisplayName("按平台查询同时锁店铺与平台，两者缺一不可")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listByPlatformFiltersBothShopAndPlatform() {
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of(order(9L, 1L, "TEMU", "T-9")));

        PageResult<UnifiedOrder> page = service.listByPlatform(1L, "TEMU", PageRequest.first(20));

        assertEquals(1, page.items().size());
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(unifiedOrderMapper).selectList(captor.capture());
        LambdaQueryWrapper<UnifiedOrder> wrapper = captor.getValue();
        String sql = wrapper.getCustomSqlSegment();
        assertTrue(sql.contains("shop_id =") && sql.contains("platform ="), sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("TEMU"),
                "平台没有作为绑定参数传入：" + wrapper.getParamNameValuePairs());
    }

    // ==================== 发货归属 ====================

    @Test
    @DisplayName("已登录但没有任何授权店铺的 OPERATOR 不能发货")
    void shipRejectsTokenWithoutShopList() {
        UserContext.setShops(List.of());
        when(unifiedOrderMapper.selectById(9L)).thenReturn(order(9L, 1L, "TEMU", "T-9"));

        assertThrows(CodeErrorException.class, () -> service.markShipped(9L, "TRK-1"));
        verifyNoInteractions(temuClient);
        verify(unifiedOrderMapper, never()).updateById(any(UnifiedOrder.class));
    }

    @Test
    @DisplayName("用户上下文完全没建立（userId 为空）时也不能发货")
    void shipRejectsContextWithoutUserId() {
        // isShopAllowed 在 userId==null 且没有 shops 列表时是放行的（定时任务与内部调用要走那条分支），
        // 发货这种会改平台侧状态的动作不能借它兜底，所以这里必须见到拒绝。
        UserContext.clear();
        when(unifiedOrderMapper.selectById(9L)).thenReturn(order(9L, 1L, "TEMU", "T-9"));

        assertThrows(CodeErrorException.class, () -> service.markShipped(9L, "TRK-1"));
        verifyNoInteractions(temuClient);
        verify(unifiedOrderMapper, never()).updateById(any(UnifiedOrder.class));
    }

    @Test
    @DisplayName("他店订单不能发货，也不回传给平台")
    void shipRejectsForeignShopOrder() {
        when(unifiedOrderMapper.selectById(9L)).thenReturn(order(9L, 2L, "TEMU", "T-9"));

        assertThrows(CodeErrorException.class, () -> service.markShipped(9L, "TRK-1"));
        verifyNoInteractions(temuClient);
    }

    @Test
    @DisplayName("ADMIN 没有店铺列表时仍可发货（全局管理语义）")
    void shipAllowsAdminWithoutShopList() {
        UserContext.setRole("ADMIN");
        UserContext.setShops(List.of());
        when(unifiedOrderMapper.selectById(9L)).thenReturn(order(9L, 1L, "TEMU", "T-9"));
        when(temuClient.markShipped("T-9", "TRK-1")).thenReturn(true);

        assertTrue(service.markShipped(9L, "TRK-1"));
    }

    @Test
    @DisplayName("平台没接受回传时，本地状态不能先变成已发货")
    void shipDoesNotMarkLocalWhenPlatformRejects() {
        when(unifiedOrderMapper.selectById(9L)).thenReturn(order(9L, 1L, "TEMU", "T-9"));
        when(temuClient.markShipped("T-9", "TRK-1")).thenReturn(false);

        assertFalse(service.markShipped(9L, "TRK-1"));
        verify(unifiedOrderMapper, never()).updateById(any(UnifiedOrder.class));
    }

    @Test
    @DisplayName("按订单自己的平台回传，且成功后才写 trackingNo 与状态")
    void shipCallsMatchingPlatformThenPersists() {
        UnifiedOrder order = order(9L, 1L, "SHEIN", "S-77");
        order.setStatus("PAID");
        when(unifiedOrderMapper.selectById(9L)).thenReturn(order);
        when(sheinClient.markShipped("S-77", "TRK-77")).thenReturn(true);

        assertTrue(service.markShipped(9L, " TRK-77 "));
        verify(temuClient, never()).markShipped(anyString(), anyString());
        verify(unifiedOrderMapper).updateById(order);
        assertEquals("SHIPPED", order.getStatus());
        assertEquals("TRK-77", order.getTrackingNo());
    }

    @Test
    @DisplayName("订单不存在时拒绝，不去调用任何平台")
    void shipRejectsMissingOrder() {
        when(unifiedOrderMapper.selectById(9L)).thenReturn(null);

        assertThrows(AttrIsNullException.class, () -> service.markShipped(9L, "TRK-1"));
        verifyNoInteractions(temuClient, sheinClient, tiktokClient);
    }

    @Test
    @DisplayName("运单号为空时先拒绝，不产生一次无效的平台回传")
    void shipRejectsBlankTrackingNo() {
        assertThrows(AttrIsNullException.class, () -> service.markShipped(9L, "   "));
        verifyNoInteractions(unifiedOrderMapper, temuClient);
    }

    // ==================== 同步汇总 ====================

    @Test
    @DisplayName("全平台同步必须区分「没有新单」和「该平台失败」")
    void syncAllReportsPerPlatformFailures() {
        when(temuClient.fetchRecentOrders(1L)).thenThrow(new IllegalStateException("temu 502"));
        when(sheinClient.fetchRecentOrders(1L)).thenReturn(List.of());
        when(tiktokClient.fetchRecentOrders(1L)).thenReturn(List.of(
                order(null, 1L, "TIKTOK", "K-1")));
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of());
        when(currencyConverter.toCny(any(BigDecimal.class), anyString())).thenReturn(new BigDecimal("9.90"));

        MultiplatformServiceImpl.OrderSyncSummary summary = service.syncAllPlatforms(1L);

        assertEquals(3, summary.attempted());
        // TEMU 抛异常，TIKTOK 与 SHEIN 都跑完了：成功是 2，不是「有异常就整体算失败」
        assertEquals(2, summary.succeeded());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.inserted());
        assertEquals(List.of("TEMU"), summary.failedPlatforms());
    }

    @Test
    @DisplayName("三个平台都没新单时 inserted=0 且 failed=0，页面才能说清是空还是坏")
    void syncAllDistinguishesEmptyFromBroken() {
        when(temuClient.fetchRecentOrders(1L)).thenReturn(List.of());
        when(sheinClient.fetchRecentOrders(1L)).thenReturn(List.of());
        when(tiktokClient.fetchRecentOrders(1L)).thenReturn(List.of());

        MultiplatformServiceImpl.OrderSyncSummary summary = service.syncAllPlatforms(1L);

        assertEquals(3, summary.succeeded());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.inserted());
        assertTrue(summary.failedPlatforms().isEmpty());
    }

    @Test
    @DisplayName("单平台同步返回真实的入库条数，去重后的重复单不再累加")
    void syncByPlatformCountsOnlyNewOrders() {
        UnifiedOrder existing = order(3L, 1L, "TEMU", "T-1");
        when(temuClient.fetchRecentOrders(1L)).thenReturn(List.of(
                order(null, 1L, "TEMU", "T-1"), order(null, 1L, "TEMU", "T-2")));
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of(existing));
        when(currencyConverter.toCny(any(BigDecimal.class), anyString())).thenReturn(new BigDecimal("1.00"));

        int inserted = service.syncByPlatform(1L, "TEMU");

        assertEquals(1, inserted);
        verify(unifiedOrderMapper).insert(any(UnifiedOrder.class));
    }

    @Test
    @DisplayName("未知平台仍然是业务异常，并且不读库")
    void syncRejectsUnknownPlatform() {
        assertThrows(AttrIsNullException.class, () -> service.syncByPlatform(1L, "WISH"));
        verifyNoInteractions(unifiedOrderMapper);
    }

    // ==================== 夹具 ====================

    private static UnifiedOrder order(Long id, Long shopId, String platform, String platformOrderNo) {
        UnifiedOrder o = new UnifiedOrder();
        o.setId(id);
        o.setShopId(shopId);
        o.setPlatform(platform);
        o.setPlatformOrderNo(platformOrderNo);
        o.setCurrency("USD");
        o.setOriginalAmount(new BigDecimal("10.00"));
        o.setStatus("PAID");
        return o;
    }
}
