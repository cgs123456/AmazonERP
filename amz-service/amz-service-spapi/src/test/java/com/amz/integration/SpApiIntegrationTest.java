package com.amz.integration;

import com.amz.auth.LwaTokenManager;
import com.amz.client.OrdersClient;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API 集成测试（真实调用 Amazon SP-API）。
 * <p>
 * 默认在 CI 中跳过，仅当环境变量 RUN_INTEGRATION_TESTS=true 时执行，避免：
 * <ul>
 *   <li>无真实凭证导致测试失败</li>
 *   <li>误触发 SP-API 调用配额消耗</li>
 *   <li>CI 环境无外网访问导致超时</li>
 * </ul>
 * <p>
 * 运行前置条件：
 * <ol>
 *   <li>配置真实 AWS 凭证（AWS_ACCESS_KEY / AWS_SECRET_KEY / LWA Client ID/Secret / Refresh Token）</ol>
 *   <li>数据库中存在测试店铺凭证（ShopCredentialStore 能加载到）</li>
 *   <li>设置环境变量 RUN_INTEGRATION_TESTS=true 和 TEST_SHOP_ID</li>
 * </ol>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
@DisplayName("SP-API 集成测试")
class SpApiIntegrationTest {

    @Autowired
    private LwaTokenManager lwaTokenManager;

    @Autowired
    private OrdersClient ordersClient;

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    /**
     * 测试 LWA Token 真实刷新：通过 ShopCredentialStore 加载凭证后调用 getToken，
     * 验证返回的 access_token 非空且为合法 JWT 格式（含两个点号）。
     */
    @Test
    @DisplayName("LWA Token 真实刷新")
    void testLwaTokenRefresh() {
        Long shopId = Long.parseLong(System.getenv().getOrDefault("TEST_SHOP_ID", "1"));
        ShopCredential credential = shopCredentialStore.get(shopId);
        assertNotNull(credential, "测试店铺凭证未配置，请检查 TEST_SHOP_ID 与数据库");

        String token = lwaTokenManager.getToken(credential);
        assertNotNull(token, "access_token 不应为 null");
        assertFalse(token.isBlank(), "access_token 不应为空");
        // LWA access_token 为 JWT 格式：header.payload.signature
        assertTrue(token.chars().filter(c -> c == '.').count() == 2,
                "access_token 应为 JWT 格式（含两个点号）");
    }

    /**
     * 测试 Orders API 真实调用：拉取最近 7 天已发货订单，验证返回列表非 null。
     * 不断言列表 size，因为测试期间可能无近期订单。
     */
    @Test
    @DisplayName("Orders API 真实调用")
    void testOrdersApiCall() {
        Long shopId = Long.parseLong(System.getenv().getOrDefault("TEST_SHOP_ID", "1"));
        String marketplaceId = System.getenv().getOrDefault("TEST_MARKETPLACE_ID", "ATVPDKIKX0DER");

        Instant createdAfter = Instant.now().minusSeconds(7L * 24 * 3600L);
        List<JsonObject> orders = ordersClient.fetchOrders(
                shopId, marketplaceId, createdAfter, List.of("Shipped"));

        assertNotNull(orders, "fetchOrders 返回不应为 null");
        // 验证区域映射正确：美国站应映射到 NA
        String region = ordersClient.mapMarketplaceToRegion(marketplaceId);
        assertTrue(List.of("NA", "EU", "FE").contains(region),
                "区域映射应在 NA/EU/FE 之中");
    }
}