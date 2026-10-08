package com.amz.controller;

import com.amz.exception.AttrIsNullException;
import com.amz.result.Result;
import com.amz.service.impl.MultiplatformServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多平台同步端点的 guarded() 包装层契约（B 桶补测，2026-10-08）。
 * <p>
 * 「能力未接入」在三家真实客户端里以 {@link UnsupportedOperationException} 表达
 * （TemuRealClient/TikTokRealClient/SheinRealClient 的 fetchProducts/fetchMessages/fetchInventory），
 * 由 controller 的 {@code guarded()} 转成 {@code Result.failure}——此前该包装层**没有任何单测**：
 * service 抛异常、controller 是否真的转成业务失败，无人守。若有人把 guarded 改掉或去掉，
 * 未接入能力就会变成 500（被读成"平台坏了"）而不是点名"未接入"。
 * <p>
 * 同时钉住反向不变量：非「未接入/属性缺失」的异常必须**继续上抛**，
 * 不能被 guarded 吞成"功能没开"（注释里写明的设计）。
 */
@DisplayName("多平台同步 guarded 包装：未接入→业务失败，其它异常仍上抛")
class MultiplatformSyncGuardedContractTest {

    private MultiplatformServiceImpl service;
    private MultiplatformController controller;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(MultiplatformServiceImpl.class);
        controller = new MultiplatformController();
        Field field = MultiplatformController.class.getDeclaredField("multiplatformService");
        field.setAccessible(true);
        field.set(controller, service);
    }

    @Test
    @DisplayName("商品同步未接入（UnsupportedOperationException）→ 业务失败，message 带原因")
    void unimplementedProductsBecomesBusinessFailure() {
        when(service.syncProducts(anyLong(), anyString()))
                .thenThrow(new UnsupportedOperationException("TEMU 平台的商品接口尚未接入"));

        Result<Integer> result = controller.syncProducts(7L, "TEMU");

        assertEquals(400, result.getCode(), "未接入能力必须转成业务失败，不能是 500");
        assertTrue(result.getMessage().contains("尚未接入"), result.getMessage());
    }

    @Test
    @DisplayName("站内信同步未接入 → 业务失败（与商品/库存同口径）")
    void unimplementedMessagesBecomesBusinessFailure() {
        when(service.syncMessages(anyLong(), anyString()))
                .thenThrow(new UnsupportedOperationException("TEMU 平台的站内信接口尚未接入"));

        Result<Integer> result = controller.syncMessages(7L, "TEMU");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("尚未接入"), result.getMessage());
    }

    @Test
    @DisplayName("库存同步未接入 → 业务失败")
    void unimplementedInventoryBecomesBusinessFailure() {
        when(service.syncInventory(anyLong(), anyString()))
                .thenThrow(new UnsupportedOperationException("TEMU 平台的库存接口尚未接入"));

        Result<Integer> result = controller.syncInventory(7L, "TEMU");

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("AttrIsNullException（属性缺失）同样转业务失败")
    void attrIsNullBecomesBusinessFailure() {
        when(service.syncProducts(anyLong(), anyString()))
                .thenThrow(new AttrIsNullException("平台标识不能为空"));

        Result<Integer> result = controller.syncProducts(7L, "");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("平台标识不能为空"), result.getMessage());
    }

    @Test
    @DisplayName("其它运行时异常必须继续上抛，不能被 guarded 吞成「功能没开」")
    void otherExceptionsPropagate() {
        when(service.syncProducts(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("数据库连接中断"));

        assertThrows(IllegalStateException.class, () -> controller.syncProducts(7L, "TEMU"),
                "真实故障要上抛给全局处理器，不能被伪装成「未接入」");
    }
}
