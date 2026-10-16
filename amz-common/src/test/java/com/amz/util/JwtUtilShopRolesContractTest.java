package com.amz.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code shop_roles} claim 的签发/解析契约。
 * <p>
 * 关键不变式是**缺席即兼容**：老 token 没有这个 claim，解析必须返回空 Map 让下游回落
 * 全局角色，而不是抛异常或返回 null 把请求打成 500。上线时不强制所有人重新登录
 * 就靠这一条。
 */
@DisplayName("JWT shop_roles claim：往返一致 + 缺席兼容")
class JwtUtilShopRolesContractTest {

    private JwtUtil newJwtUtil() {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secretKey", "test-secret-key-at-least-32-bytes-long!!");
        ReflectionTestUtils.setField(util, "issuer", "amz-erp");
        ReflectionTestUtils.setField(util, "audience", "amz-erp-client");
        ReflectionTestUtils.setField(util, "expireTime", 86400000L);
        ReflectionTestUtils.setField(util, "refreshExpireTime", 604800000L);
        util.init();
        return util;
    }

    @Test
    @DisplayName("多店不同角色往返一致")
    void roundTripMultipleShops() {
        JwtUtil util = newJwtUtil();
        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(1001L, "OPERATOR");
        roles.put(2002L, "VIEWER");
        roles.put(3003L, "ADMIN");

        String token = util.createToken(1, List.of(1001L, 2002L, 3003L), "OPERATOR", roles);
        Map<Long, String> parsed = util.parseTokenShopRoles(token);

        assertEquals(3, parsed.size());
        assertEquals("OPERATOR", parsed.get(1001L));
        assertEquals("VIEWER", parsed.get(2002L));
        assertEquals("ADMIN", parsed.get(3003L));
    }

    @Test
    @DisplayName("shopId 以 Long 键往返：JSON 键是字符串，解析必须还原成 Long")
    void shopIdsComeBackAsLongs() {
        JwtUtil util = newJwtUtil();
        String token = util.createToken(1, List.of(900000000000001000L), "OPERATOR",
                Map.of(900000000000001000L, "OPERATOR"));

        Map<Long, String> parsed = util.parseTokenShopRoles(token);

        // 大 shopId 走字符串键往返，若实现漏了类型还原，这里会是 0 条
        assertTrue(parsed.containsKey(900000000000001000L),
                "shopId 未能按 Long 还原：" + parsed.keySet());
    }

    @Test
    @DisplayName("不带 shop_roles 的老 token 解析为空 Map，不抛异常（上线兼容的关键）")
    void legacyTokenWithoutClaimParsesToEmptyMap() {
        JwtUtil util = newJwtUtil();
        // 三参重载 = 老签发表单，不带 shop_roles
        String legacy = util.createToken(1, List.of(1001L), "OPERATOR");

        Map<Long, String> parsed = util.parseTokenShopRoles(legacy);

        assertNotNull(parsed, "老 token 必须返回空 Map 而不是 null");
        assertTrue(parsed.isEmpty(), "老 token 不该解析出任何店铺角色");
    }

    @Test
    @DisplayName("传空 Map 等价于不带该 claim（不写入空对象占空间）")
    void emptyMapDoesNotAddClaim() {
        JwtUtil util = newJwtUtil();
        String token = util.createToken(1, List.of(1001L), "OPERATOR", Map.of());

        assertTrue(util.parseTokenShopRoles(token).isEmpty());
    }

    @Test
    @DisplayName("空值/空白角色条目被跳过，不污染 claim")
    void blankRoleEntriesAreSkipped() {
        JwtUtil util = newJwtUtil();
        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(1001L, "OPERATOR");
        roles.put(2002L, "   ");
        roles.put(null, "ADMIN");

        String token = util.createToken(1, List.of(1001L), "OPERATOR", roles);
        Map<Long, String> parsed = util.parseTokenShopRoles(token);

        assertEquals(1, parsed.size());
        assertEquals("OPERATOR", parsed.get(1001L));
    }

    @Test
    @DisplayName("原有 claim 不受影响：role 与 shops 仍能正常解析")
    void existingClaimsUnaffected() {
        JwtUtil util = newJwtUtil();
        String token = util.createToken(1, List.of(1001L, 2002L), "ADMIN",
                Map.of(1001L, "VIEWER"));

        assertEquals("1", util.parseToken(token));
        assertEquals("ADMIN", util.parseTokenRole(token));
        assertEquals(List.of(1001L, 2002L), util.parseTokenShops(token));
        assertEquals("VIEWER", util.parseTokenShopRoles(token).get(1001L));
    }
}
