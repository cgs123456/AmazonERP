package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表写入守卫的授权档位。
 * <p>
 * 存在动因：{@code /report/v2/**} 与 {@code /report/profit/allocation} 这 5 个摄取端点标了
 * {@code @ShopScoped}，但 shopId 全在 {@code @RequestBody} 实体里，切面解析不到就放行；
 * service 层此前也没有兜底。这里锁的是「守卫本身按严格档判」，接线由
 * {@code ReportIngestTenantWiringTest} 负责。
 */
@DisplayName("报表写入守卫：严格档店铺归属校验")
class ReportTenantGuardTest {

    private final ReportTenantGuard guard = new ReportTenantGuard();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("授权列表里没有的店铺：直接拒，且原因可归因")
    void foreignShopIsRejected() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> guard.requireShopAccess(2L, "利润明细"));
        assertTrue(ex.getMessage().contains("2L") || ex.getMessage().contains("2"), ex.getMessage());
    }

    @Test
    @DisplayName("合法 JWT 但缺 shops claim 不能等于「可访问所有店铺」")
    void missingShopListIsNotACheck() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        assertThrows(CodeErrorException.class, () -> guard.requireShopAccess(1L, "销售日报"));
    }

    @Test
    @DisplayName("完全没有身份时也拒：这 5 个端点今天没有合法的内部调用方")
    void anonymousContextIsRejected() {
        assertThrows(CodeErrorException.class, () -> guard.requireShopAccess(1L, "经营概览"));
    }

    @Test
    @DisplayName("命中授权列表时放行")
    void authorizedShopPasses() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L, 3L));
        assertDoesNotThrow(() -> guard.requireShopAccess(3L, "费用分摊"));
    }

    @Test
    @DisplayName("ADMIN 短路，与切面既有语义一致")
    void adminPassesWithoutShopList() {
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        assertDoesNotThrow(() -> guard.requireShopAccess(999L, "库存周转"));
    }

    @Test
    @DisplayName("shopId 为空报属性缺失，而不是被当成「无限制」")
    void nullShopIdIsAttrMissing() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        assertThrows(AttrIsNullException.class, () -> guard.requireShopAccess(null, "利润明细"));
    }
}
