package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import com.amz.connector.SpApiEndpointNotAllowedException;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiHostPolicy;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.StubCredentialStore;
import com.amz.testsupport.TestCredentials;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-51：端点覆盖的安全边界（prod 拒绝启动 / 非白名单拒绝 / token 不出网）。
 * <p>
 * 覆盖键（{@code spapi.base-url-override} / {@code spapi.lwa-endpoint-override}）是新增攻击面：
 * 一旦允许把请求发往任意主机，等于把 LWA access token 与业务 payload 交给该主机。
 * 因此三条规则都必须 fail-closed，且**在注入 {@code x-amz-access-token} 之前**生效：
 * <ol>
 *   <li>prod profile 下任一覆盖键非空 → 应用拒绝启动（不是打一条 warn 继续跑）；</li>
 *   <li>非生产也限主机：官方主机 + 默认回环 + {@code spapi.allowlist} 之外的地址直接拒绝；</li>
 *   <li>被拒绝时传输层不得产生任何业务请求（token 根本不出网）。</li>
 * </ol>
 * 证据类型：E2（桩回放 / 契约构造）。真实沙箱联调（E4）必须由 runbook 在有凭证环境执行。
 */
@DisplayName("P0-51 端点覆盖安全边界（prod / allowlist / token 不出网）")
class SpApiEndpointOverrideSafetyTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String TOKEN = "Atza|override-safety-token";
    private static final String OFFICIAL_NA = "https://sellingpartnerapi-na.amazon.com";
    private static final String ROGUE_HOST = "stub.evil.example.com";

    private static SpApiConfig config() {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setLwaEndpoint(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        return config;
    }

    private static SpApiRequestFactory requestFactory(SpApiConfig config, SpApiEndpointResolver resolver) {
        return new SpApiRequestFactory(new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
    }

    @Test
    @DisplayName("prod + base-url-override 非空 → 拒绝启动（IllegalStateException，含配置键名）")
    void prodRejectsBaseUrlOverride() {
        SpApiConfig config = config();
        config.setBaseUrlOverride("http://127.0.0.1:8080");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SpApiEndpointResolver(config, true));

        assertTrue(e.getMessage().contains(SpApiEndpointResolver.OVERRIDE_PROPERTY), e.getMessage());
        assertTrue(e.getMessage().contains("127.0.0.1:8080"), e.getMessage());
    }

    @Test
    @DisplayName("prod + lwa-endpoint-override 非空 → 同样拒绝启动")
    void prodRejectsLwaEndpointOverride() {
        SpApiConfig config = config();
        config.setLwaEndpointOverride("http://127.0.0.1:8080/auth/o2/token");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SpApiEndpointResolver(config, true));

        assertTrue(e.getMessage().contains(SpApiEndpointResolver.LWA_OVERRIDE_PROPERTY), e.getMessage());
    }

    @Test
    @DisplayName("prod 且覆盖键为空 → 官方主机照常可用（不误伤生产）")
    void prodWithoutOverridesUsesOfficialHosts() {
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config(), true);

        assertFalse(resolver.isOverrideActive());
        assertTrue(resolver.isProduction());
        SpApiEndpointResolver.Endpoint endpoint = resolver.resolve("NA");
        assertEquals(OFFICIAL_NA, endpoint.baseUrl());
        assertEquals("sellingpartnerapi-na.amazon.com", endpoint.host());
        assertFalse(endpoint.overridden());
        assertEquals(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT, resolver.lwaEndpoint());
        assertTrue(resolver.hostPolicy().isAllowed("sellingpartnerapi-eu.amazon.com"));
        assertTrue(resolver.hostPolicy().isAllowed("sellingpartnerapi-fe.amazon.com"));
        assertFalse(resolver.hostPolicy().isAllowed(ROGUE_HOST));
    }

    @Test
    @DisplayName("非生产 + 非白名单主机 → 构造即拒绝（异常带稳定错误码与主机名）")
    void nonAllowlistedHostRejectedAtConstruction() {
        SpApiConfig config = config();
        config.setBaseUrlOverride("https://" + ROGUE_HOST);

        SpApiEndpointNotAllowedException e = assertThrows(SpApiEndpointNotAllowedException.class,
                () -> new SpApiEndpointResolver(config, false));

        assertEquals(SpApiEndpointNotAllowedException.CODE_ENDPOINT_NOT_ALLOWED, e.code());
        assertEquals(ROGUE_HOST, e.host());
        assertTrue(e.getMessage().contains(ROGUE_HOST), e.getMessage());
    }

    @Test
    @DisplayName("非生产 + 非 http/https scheme → 拒绝（file:// / ftp:// 不得成为出站通道）")
    void nonHttpSchemeRejected() {
        SpApiConfig config = config();
        config.setBaseUrlOverride("file:///tmp/stub");

        assertThrows(SpApiEndpointNotAllowedException.class,
                () -> new SpApiEndpointResolver(config, false));
    }

    @Test
    @DisplayName("非生产 + 回环地址 → 覆盖生效；显式 allowlist 主机 → 覆盖生效")
    void loopbackAndExplicitAllowlistAreAccepted() {
        SpApiConfig loopback = config();
        loopback.setBaseUrlOverride("http://127.0.0.1:19099/");
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(loopback, false);

        assertTrue(resolver.isOverrideActive());
        SpApiEndpointResolver.Endpoint endpoint = resolver.resolve("NA");
        assertEquals("http://127.0.0.1:19099", endpoint.baseUrl(), "末尾斜杠必须归一");
        assertEquals("127.0.0.1", endpoint.host());
        assertTrue(endpoint.overridden());

        SpApiConfig allowlisted = config();
        allowlisted.setAllowlist(new ArrayList<>(List.of("stub.internal.example.com")));
        allowlisted.setBaseUrlOverride("http://stub.internal.example.com:9001");
        SpApiEndpointResolver allowlistedResolver = new SpApiEndpointResolver(allowlisted, false);

        assertEquals("stub.internal.example.com", allowlistedResolver.resolve("EU").host());
        assertTrue(allowlistedResolver.hostPolicy().allowlist().contains("stub.internal.example.com"));
    }

    @Test
    @DisplayName("覆盖粒度是主机+基址：NA/EU/FE 都指向同一桩地址")
    void overrideAppliesToAllRegions() {
        SpApiConfig config = config();
        config.setBaseUrlOverride("http://127.0.0.1:19099");
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);

        for (String region : List.of("NA", "EU", "FE")) {
            assertEquals("http://127.0.0.1:19099", resolver.resolve(region).baseUrl(), region);
            assertTrue(resolver.resolve(region).overridden(), region);
        }
        // 未开启覆盖时未知 region 仍然 fail-closed
        assertThrows(com.amz.connector.UnknownMarketplaceException.class,
                () -> SpApiEndpointResolver.officialOnly().resolve("NOT_A_REGION"));
    }

    @Test
    @DisplayName("LWA 覆盖：非生产可指向白名单主机；prod 直接拒绝")
    void lwaEndpointOverrideIsHonoured() {
        SpApiConfig config = config();
        config.setLwaEndpointOverride("http://127.0.0.1:19099/auth/o2/token");
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);

        assertTrue(resolver.lwaEndpointOverridden());
        assertEquals("http://127.0.0.1:19099/auth/o2/token", resolver.lwaEndpoint());

        RecordingHttpTransport transport = RecordingHttpTransport.json(
                "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
        LwaTokenManager manager = new LwaTokenManager(transport, config, resolver);

        assertEquals(TOKEN, manager.getToken(TestCredentials.northAmerica()));
        assertEquals("http://127.0.0.1:19099/auth/o2/token",
                transport.lastRequest().uri().toString(), "LWA 交换必须走覆盖端点而非官方主机");
    }

    @Test
    @DisplayName("请求构造点拒绝非白名单主机：x-amz-access-token 不进入任何请求")
    void requestFactoryRefusesNonAllowlistedHostBeforeAttachingToken() {
        SpApiRequestFactory factory = requestFactory(config(), SpApiEndpointResolver.officialOnly());

        SpApiEndpointNotAllowedException e = assertThrows(SpApiEndpointNotAllowedException.class,
                () -> factory.spApi("GET", "https://" + ROGUE_HOST, ROGUE_HOST, "us-east-1",
                        "/orders/v0/orders", null, null, TOKEN, null, null));

        assertTrue(e.getMessage().contains(ROGUE_HOST), e.getMessage());
        assertFalse(e.getMessage().contains(TOKEN), "异常信息不得回显 access token：" + e.getMessage());
    }

    @Test
    @DisplayName("endpoint 主机与签名 host 不一致 → 拒绝（防止签名作用域与实际目标错位）")
    void requestFactoryRefusesHostMismatch() {
        SpApiRequestFactory factory = requestFactory(config(), SpApiEndpointResolver.officialOnly());

        SpApiEndpointNotAllowedException e = assertThrows(SpApiEndpointNotAllowedException.class,
                () -> factory.spApi("GET", OFFICIAL_NA, ROGUE_HOST, "us-east-1",
                        "/orders/v0/orders", null, null, TOKEN, null, null));

        assertTrue(e.getMessage().contains(ROGUE_HOST), e.getMessage());
    }

    @Test
    @DisplayName("客户端拿到非白名单覆盖端点：token 已在手也不出网，传输层只有 LWA 交换")
    void clientRefusesNonAllowlistedEndpointBeforeSendingToken() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("api.amazon.com".equals(request.uri().getHost())) {
                return new RecordingHttpTransport.Reply(200,
                        "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
            }
            return new RecordingHttpTransport.Reply(200, "{\"payload\":{\"Orders\":[]}}");
        });
        SpApiConfig config = config();

        // 模拟「配置层被绕过」的最坏情形：解析器直接返回非白名单主机
        SpApiEndpointResolver rogue = Mockito.mock(SpApiEndpointResolver.class);
        Mockito.when(rogue.resolve("NA")).thenReturn(
                new SpApiEndpointResolver.Endpoint(ROGUE_HOST, "https://" + ROGUE_HOST, true));
        Mockito.when(rogue.isOverrideActive()).thenReturn(true);
        Mockito.when(rogue.lwaEndpoint()).thenReturn(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        // 白名单策略仍是真实默认值：配置层被绕过时，请求构造点必须独立拦下非白名单主机
        Mockito.when(rogue.hostPolicy()).thenReturn(SpApiHostPolicy.defaults());
        Mockito.when(rogue.baseUrlOverride()).thenReturn("https://" + ROGUE_HOST);

        LwaTokenManager tokenManager = new LwaTokenManager(transport, config, rogue);
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        OrdersClient ordersClient = new OrdersClient(transport, tokenManager, credentials,
                new SpiRateLimiter(), requestFactory(config, rogue), noMetrics());

        assertThrows(SpApiEndpointNotAllowedException.class, () -> ordersClient.fetchOrders(
                SHOP_ID, MARKETPLACE_ID, Instant.parse("2026-09-01T00:00:00Z"), null));

        List<String> hosts = transport.requests().stream()
                .map(request -> request.uri().getHost())
                .distinct()
                .toList();
        assertEquals(List.of("api.amazon.com"), hosts, "除 LWA 交换外不得有任何出站请求：" + hosts);
        for (HttpRequest request : transport.requests()) {
            assertNull(request.headers().firstValue("x-amz-access-token").orElse(null),
                    "凭证不得发往非官方主机：" + request.uri());
        }
    }

    @Test
    @DisplayName("白名单策略：精确匹配，不做后缀/通配/DNS 解析")
    void hostPolicyMatchesExactly() {
        SpApiHostPolicy policy = SpApiHostPolicy.of(List.of("stub.internal.example.com"));

        assertTrue(policy.isAllowed("stub.internal.example.com"));
        assertTrue(policy.isAllowed(" STUB.INTERNAL.EXAMPLE.COM "), "大小写与首尾空白不敏感");
        assertFalse(policy.isAllowed("evil-stub.internal.example.com"), "禁止后缀/子串匹配");
        assertFalse(policy.isAllowed("stub.internal.example.com.evil.test"), "禁止后缀匹配");
        assertFalse(policy.isAllowed("*.internal.example.com"), "禁止通配符");
        assertFalse(policy.isAllowed(null));
        assertFalse(policy.isAllowed("  "));
        assertTrue(policy.isAllowed("127.0.0.1"), "默认回环白名单始终存在");
        assertTrue(policy.isAllowed("localhost"));
        assertTrue(policy.isAllowed("[::1]"), "IPv6 方括号需归一");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return Mockito.mock(ObjectProvider.class);
    }
}
