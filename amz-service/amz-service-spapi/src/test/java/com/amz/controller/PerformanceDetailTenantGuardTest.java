package com.amz.controller;

import com.amz.context.UserContext;
import com.amz.mapper.ShopPerformanceMapper;
import com.amz.model.ShopPerformance;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 店铺绩效留痕详情的越权拦截。
 *
 * <p><b>缺陷原貌（§S33.41）</b>：{@code GET /spapi/performance/{id}} 上标了
 * {@code @ShopScoped}，但那枚注解在这里是空的——{@code ShopIdGuardAspect} 用
 * {@code ShopIdParameterResolver} 只认<b>名为 shopId</b> 的
 * {@code @RequestParam}/{@code @PathVariable} 参数，本方法的路径参数叫 {@code id}，
 * 解析不到就走「未找到 shopId 参数，跳过」分支直接放行。
 *
 * <p>而 {@code amz_shop_performance.id} 是自增主键、{@code raw_content} 是整份
 * SP-API 报表原文。也就是说任何已登录用户按 id 递增就能逐条读遍所有店铺的绩效原文。
 * 这不是「看不到数据」，是「看得到别人的数据」。
 *
 * <p>修法：方法内逐行严格判定（{@code isShopAllowedStrict}），越权与不存在共用同一句
 * 文案——否则这个端点会退化成「哪条留痕存在」的探针。
 *
 * <p><b>为什么断言文案相同</b>：两条文案一旦分开，攻击者就能用同一个 id
 * 区分「不存在」和「存在但不属于你」，从而把整表 id 枚举一遍摸清数据规模。
 * 这条断言锁的是那个区分被消掉。
 */
@DisplayName("店铺绩效留痕详情：按 id 直读必须逐行校验店铺归属")
class PerformanceDetailTenantGuardTest {

    private static final long OWN_SHOP = 1L;
    private static final long FOREIGN_SHOP = 99L;

    private PerformanceController controller;
    private ShopPerformanceMapper performanceMapper;

    @BeforeEach
    void setUp() {
        performanceMapper = mock(ShopPerformanceMapper.class);
        controller = new PerformanceController();
        ReflectionTestUtils.setField(controller, "performanceMapper", performanceMapper);
        ReflectionTestUtils.setField(controller, "maxPollAttempts", 3);
        ReflectionTestUtils.setField(controller, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(controller, "pollDeadlineMs", 10L);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    private static ShopPerformance row(long id, Long shopId) {
        ShopPerformance p = new ShopPerformance();
        p.setId(id);
        p.setShopId(shopId);
        p.setRawContent("{\"odr\":0.42}");
        return p;
    }

    private static void asOperatorOf(long shopId) {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shopId));
    }

    @Test
    @DisplayName("本店铺的留痕可以读到原文")
    void ownShopRowIsReturned() {
        asOperatorOf(OWN_SHOP);
        when(performanceMapper.selectById(11L)).thenReturn(row(11L, OWN_SHOP));

        Result<ShopPerformance> res = controller.detail(11L);

        assertEquals(200, res.getCode());
        assertNotNull(res.getData());
        assertEquals("{\"odr\":0.42}", res.getData().getRawContent());
    }

    @Test
    @DisplayName("别人店铺的留痕：拒绝且不返回任何字段（含原文）")
    void foreignShopRowIsRejected() {
        asOperatorOf(OWN_SHOP);
        when(performanceMapper.selectById(12L)).thenReturn(row(12L, FOREIGN_SHOP));

        Result<ShopPerformance> res = controller.detail(12L);

        assertEquals(400, res.getCode(), "越权必须被拒绝，实际 code=" + res.getCode());
        assertNull(res.getData(), "越权时不得把原文带回响应体——那是这次要防的东西本身");
    }

    @Test
    @DisplayName("越权与不存在的文案完全一致，不做 id 探针")
    void forbiddenAndMissingAreIndistinguishable() {
        asOperatorOf(OWN_SHOP);
        when(performanceMapper.selectById(13L)).thenReturn(row(13L, FOREIGN_SHOP));
        when(performanceMapper.selectById(14L)).thenReturn(null);

        Result<ShopPerformance> forbidden = controller.detail(13L);
        Result<ShopPerformance> missing = controller.detail(14L);

        assertEquals(forbidden.getMessage(), missing.getMessage(),
                "文案一旦分开，攻击者就能枚举 id 摸清数据规模");
        assertTrue(forbidden.getMessage().contains("不存在或无权访问"),
                "实际文案：" + forbidden.getMessage());
    }

    @Test
    @DisplayName("完全没有认证上下文时 fail-closed")
    void withoutContextFailsClosed() {
        UserContext.clear();
        when(performanceMapper.selectById(15L)).thenReturn(row(15L, OWN_SHOP));

        Result<ShopPerformance> res = controller.detail(15L);

        assertEquals(400, res.getCode(), "无上下文不得放行：isShopAllowedStrict 对空 shops 返回 false");
        assertNull(res.getData());
    }

    @Test
    @DisplayName("id 为空时直接拒绝，不进查询")
    void nullIdIsRejectedBeforeQuery() {
        asOperatorOf(OWN_SHOP);

        Result<ShopPerformance> res = controller.detail(null);

        assertEquals(400, res.getCode());
        verify(performanceMapper, org.mockito.Mockito.never()).selectById(any());
    }
}
