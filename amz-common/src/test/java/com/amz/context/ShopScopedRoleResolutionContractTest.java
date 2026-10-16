package com.amz.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 店铺级角色解析（{@link UserContext#resolveRole(Long)}）的契约。
 * <p>
 * 存在动因见 HANDOFF §S22：{@code amz_user_shop.role} 此前能配、能显示、判定时从不生效，
 * 管理员以为自己收窄了权限而实际什么都没发生。本测试钉住「接通之后」的判定顺序。
 */
@DisplayName("店铺级角色解析：店铺级优先、全局兜底")
class ShopScopedRoleResolutionContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("同一用户在不同店铺拿到不同角色（这张表存在的全部意义）")
    void sameUserDifferentRolesPerShop() {
        UserContext.setRole("OPERATOR");
        Map<Long, String> byShop = new LinkedHashMap<>();
        byShop.put(1001L, "OPERATOR");
        byShop.put(2002L, "VIEWER");
        UserContext.setShopRoles(byShop);

        assertEquals("OPERATOR", UserContext.resolveRole(1001L));
        assertEquals("VIEWER", UserContext.resolveRole(2002L));
    }

    @Test
    @DisplayName("店铺角色可以比全局角色更低（收窄生效）")
    void shopRoleCanBeNarrowerThanGlobal() {
        UserContext.setRole("ADMIN");
        UserContext.setShopRoles(Map.of(2002L, "VIEWER"));

        assertEquals("VIEWER", UserContext.resolveRole(2002L));
    }

    @Test
    @DisplayName("店铺角色可以比全局角色更高（提权也生效，管理员有意配置）")
    void shopRoleCanBeBroaderThanGlobal() {
        UserContext.setRole("VIEWER");
        UserContext.setShopRoles(Map.of(1001L, "OPERATOR"));

        assertEquals("OPERATOR", UserContext.resolveRole(1001L));
    }

    @Test
    @DisplayName("未绑定的店铺回落全局角色（老 token / 未分配店铺的行为不变）")
    void unboundShopFallsBackToGlobalRole() {
        UserContext.setRole("OPERATOR");
        UserContext.setShopRoles(Map.of(1001L, "VIEWER"));

        assertEquals("OPERATOR", UserContext.resolveRole(9999L));
    }

    @Test
    @DisplayName("老 token 没有 shop_roles claim 时行为与改动前完全一致")
    void legacyTokenWithoutShopRolesBehavesAsBefore() {
        UserContext.setRole("OPERATOR");
        // 老 token 解析出来是空 Map，BaseAuthInterceptor 照常写入
        UserContext.setShopRoles(Map.of());

        assertEquals("OPERATOR", UserContext.resolveRole(1001L));

        // 完全没写入（白名单/内部调用路径）时同样回落
        UserContext.setShopRoles(null);
        assertEquals("OPERATOR", UserContext.resolveRole(1001L));
    }

    @Test
    @DisplayName("shopId 为 null 时必然回落全局角色")
    void nullShopIdFallsBackToGlobalRole() {
        UserContext.setRole("OPERATOR");
        UserContext.setShopRoles(Map.of(1001L, "VIEWER"));

        assertEquals("OPERATOR", UserContext.resolveRole(null));
    }

    @Test
    @DisplayName("店铺角色为空白串时按「未配置」处理，不返回空角色让下游误判")
    void blankShopRoleFallsBackToGlobalRole() {
        UserContext.setRole("OPERATOR");
        UserContext.setShopRoles(Map.of(1001L, "   "));

        assertEquals("OPERATOR", UserContext.resolveRole(1001L));
    }

    @Test
    @DisplayName("全局角色也没有时返回 null，由切面 fail-closed 拒绝")
    void noRoleAtAllReturnsNull() {
        assertNull(UserContext.resolveRole(1001L));
        assertNull(UserContext.resolveRole(null));
    }

    @Test
    @DisplayName("clear() 必须一并清掉店铺角色，否则线程复用会把上一个用户的地图带给下一个")
    void clearRemovesShopRoles() {
        UserContext.setRole("OPERATOR");
        UserContext.setShopRoles(Map.of(1001L, "ADMIN"));
        assertEquals("ADMIN", UserContext.resolveRole(1001L));

        UserContext.clear();

        // 线程池复用同一线程时，残留的 shop_roles 会让下一个请求按上一个用户的店铺角色判定
        assertNull(UserContext.getShopRoles());
        assertNull(UserContext.resolveRole(1001L));
    }
}
