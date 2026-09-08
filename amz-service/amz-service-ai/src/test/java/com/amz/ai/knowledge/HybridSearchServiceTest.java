package com.amz.ai.knowledge;

import com.amz.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HybridSearchService RRF 与店铺范围测试")
class HybridSearchServiceTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("双路都命中的块排前面")
    void testFuseRankings() {
        List<String> ranked = HybridSearchService.fuseRankings(
                List.of("a", "b", "c"), List.of("c", "a"), 60.0);
        assertEquals(List.of("a", "c", "b"), ranked);
    }

    @Test
    @DisplayName("显式 shopId 优先")
    void testResolveScopesExplicit() {
        assertEquals(List.of(7L), HybridSearchService.resolveScopes(7L));
    }

    @Test
    @DisplayName("缺省时使用授权店铺列表")
    void testResolveScopesAuthorized() {
        UserContext.setShops(new ArrayList<>(List.of(2L, 1L)));
        assertEquals(List.of(2L, 1L), HybridSearchService.resolveScopes(null));
    }

    @Test
    @DisplayName("无授权上下文时放行全量（空过滤）")
    void testResolveScopesEmpty() {
        assertTrue(HybridSearchService.resolveScopes(null).isEmpty());
    }
}
