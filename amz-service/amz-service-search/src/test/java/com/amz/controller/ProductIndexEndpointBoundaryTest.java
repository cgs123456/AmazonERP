package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引端点的鉴权边界：服务间通知与运维操作必须彻底分开。
 * <p>
 * <b>为什么需要这条测试</b>——本轮核实出一个真实越权路径：
 * {@code BaseAuthInterceptor.preHandle} 的判断是
 * <pre>{@code
 * if (!hasUserToken && hasServiceToken && hasInternalServiceAccess(handler)) { ... }
 * return authenticateUser(...);
 * }</pre>
 * 也就是说 {@code @InternalServiceAccess} <b>只允许服务令牌进来，不排斥用户 JWT</b>。
 * 一个挂在普通业务路径上、带 {@code @InternalServiceAccess} 但无 {@code @RequireRole}
 * 的端点，任何登录用户都能带着自己的 token 调用。
 * <p>
 * 对索引端点而言这不是理论问题：
 * <ul>
 *   <li>{@code /upsert} 被越权 → 任意登录用户可向共享搜索索引注入任意商品；</li>
 *   <li>重建读端点被越权 → 任何登录用户可拉走全库商品（跨租户）。</li>
 * </ul>
 * 唯一同时满足「服务令牌可进」「用户 JWT 不可进」「公网不可达」的是 {@code /internal/**} 前缀。
 * 本测试把这条约束钉死：任何人把端点挪回普通路径都会立刻红。
 */
@DisplayName("索引端点边界：通知走 /internal，运维走业务路径且锁 ADMIN")
class ProductIndexEndpointBoundaryTest {

    @Test
    @DisplayName("服务间通知端点必须挂在 /internal/** 下（否则用户 JWT 可越权调用）")
    void internalEndpointsLiveUnderInternalPrefix() {
        RequestMapping mapping = ProductIndexInternalController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "ProductIndexInternalController 缺少 @RequestMapping");
        String basePath = mapping.value()[0];
        assertTrue(basePath.equals("/internal") || basePath.startsWith("/internal/"),
                "服务间通知端点必须在 /internal/** 下，实际：" + basePath
                        + "（否则 BaseAuthInterceptor 在带用户 token 时不会走服务令牌分支，"
                        + "@InternalServiceAccess 形同不存在）");

        int checked = 0;
        for (Method method : ProductIndexInternalController.class.getDeclaredMethods()) {
            if (!isMapping(method)) {
                continue;
            }
            checked++;
            assertNotNull(method.getAnnotation(InternalServiceAccess.class),
                    method.getName() + " 缺少 @InternalServiceAccess 白名单");
            assertFalse(method.isAnnotationPresent(RequireRole.class),
                    method.getName() + " 不应有 @RequireRole：服务令牌没有角色，"
                            + "加了会让内部调用被角色切面拦截");
        }
        assertEquals(3, checked, "内部端点数量变化必须重新审查鉴权（新增/删除都要看）");
    }

    @Test
    @DisplayName("运维端点留在业务路径且必须锁 ADMIN/VIEWER（不能挪进 /internal，否则用户进不来）")
    void operationalEndpointsStayOnBusinessPathAndRequireRole() {
        RequestMapping mapping = ProductIndexController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "ProductIndexController 缺少 @RequestMapping");
        String basePath = mapping.value()[0];
        assertFalse(basePath.equals("/internal") || basePath.startsWith("/internal/"),
                "运维端点不能挂在 /internal/** 下：网关对该前缀直接 404，ADMIN 用户将无法访问");

        int checked = 0;
        for (Method method : ProductIndexController.class.getDeclaredMethods()) {
            if (!isMapping(method)) {
                continue;
            }
            checked++;
            RequireRole role = method.getAnnotation(RequireRole.class);
            assertNotNull(role, method.getName() + " 缺少 @RequireRole：运维操作必须锁角色");
            // 重建会遍历全量商品并写 ES，只允许 ADMIN
            if ("rebuild".equals(method.getName())) {
                assertEquals(1, role.value().length, "rebuild 只应允许 ADMIN");
                assertEquals("ADMIN", role.value()[0], "rebuild 必须只允许 ADMIN");
            }
        }
        assertEquals(2, checked, "运维端点数量变化必须重新审查鉴权");
    }

    @Test
    @DisplayName("两个控制器的路径前缀不得重叠，避免同一路径有两种鉴权语义")
    void controllersDoNotSharePathPrefix() {
        String internal = ProductIndexInternalController.class
                .getAnnotation(RequestMapping.class).value()[0];
        String operational = ProductIndexController.class
                .getAnnotation(RequestMapping.class).value()[0];

        assertFalse(operational.startsWith(internal + "/") || internal.startsWith(operational + "/"),
                "两个前缀存在包含关系，路由会互相遮蔽：" + internal + " vs " + operational);
    }

    private static boolean isMapping(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }
}
