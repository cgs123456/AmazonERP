package com.amz.preflight;

import com.amz.auth.LwaTokenManager;
import com.amz.config.SpApiConfig;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.UnknownMarketplaceException;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.StubCredentialStore;
import com.amz.testsupport.TestCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连通性自检的分层定界与错误分类契约（零 socket，进程内桩）。
 *
 * <p><b>它锁住的核心不变量：</b>
 * <ul>
 *   <li>失败必须<b>停在对应层</b>，并被翻译成人话 remediation，而不是回抛异常类名；</li>
 *   <li>未执行的阶段是 SKIP，且 SKIP 会让 ready=false（不得把「没跑」读成「没问题」）；</li>
 *   <li>报告里不含 refresh_token / client_secret / access_token。</li>
 * </ul>
 *
 * <p><b>证据等级 E2</b>（桩回放）：证明的是分支与分类逻辑，不证明 Amazon 真实接受请求。
 */
@DisplayName("SP-API 分层连通性自检（凭证 / LWA / 只读调用）")
class SpApiConnectivityPreflightTest {

    private static final String OP = "sellers.getMarketplaceParticipations";
    private static final String PATH = "/sellers/v1/marketplaceParticipations";

    private static final String LWA_OK = "{"
            + "\"access_token\":\"Atza|IQEBLjAsAhRmHjNgHpi0U-Dme37rR6CuUpSREXAMPLE\","
            + "\"token_type\":\"bearer\","
            + "\"expires_in\":3600"
            + "}";

    @Test
    @DisplayName("三段全通：ready=true，且报告给出真实官方主机与非覆盖标记")
    void allStagesPass() {
        ShopCredential credential = TestCredentials.northAmerica();
        CountingProbe probe = new CountingProbe("marketplaceParticipations=3", null);
        SpApiConnectivityPreflight preflight = build(credential, RecordingHttpTransport.json(LWA_OK), probe);

        PreflightReport report = preflight.check(credential.getShopId());

        assertTrue(report.ready(), "三段全通必须 ready=true，stages=" + report.stages());
        assertEquals(3, report.stages().size());
        assertEquals(List.of(PreflightReport.STAGE_CREDENTIAL, PreflightReport.STAGE_LWA,
                PreflightReport.STAGE_READ_API),
                report.stages().stream().map(PreflightStage::name).toList());
        assertEquals("ATVPDKIKX0DER", report.marketplaceId());
        assertEquals("NA", report.region());
        assertNotNull(report.host(), "必须给出实际出站主机，否则运维无法判断打的是哪个区域");
        assertTrue(report.host().contains("na"), "NA 区域主机应含 na，实际=" + report.host());
        assertFalse(report.endpointOverridden(), "未配置覆盖时不得标记 override");
        assertEquals(1, probe.calls);
        assertTrue(report.nextAction().contains("联调"), "全通时的 nextAction 必须写明仍不是联调证据");
    }

    @Test
    @DisplayName("无凭证：只产出一个 FAIL 阶段，不伪造后续结论")
    void missingCredentialFailsFast() {
        ShopCredential credential = TestCredentials.northAmerica();
        CountingProbe probe = new CountingProbe("marketplaceParticipations=1", null);
        SpApiConnectivityPreflight preflight = build(null, RecordingHttpTransport.json(LWA_OK), probe);

        PreflightReport report = preflight.check(credential.getShopId());

        assertFalse(report.ready());
        assertEquals(1, report.stages().size(), "凭证缺失必须立即返回，不得继续编造后续阶段");
        PreflightStage stage = report.stages().get(0);
        assertEquals(PreflightReport.STAGE_CREDENTIAL, stage.name());
        assertEquals(SpApiConnectivityPreflight.CODE_CREDENTIAL_MISSING, stage.errorCode());
        assertTrue(stage.remediation().contains("导入"), "remediation 必须给出导入动作，而非异常类名");
        assertNull(report.marketplaceId());
        assertNull(report.host());
        assertEquals(0, probe.calls);
    }

    @Test
    @DisplayName("凭证结构不完整：只报字段名，不报字段值")
    void incompleteCredentialReportsFieldNamesOnly() {
        ShopCredential credential = TestCredentials.northAmerica();
        credential.setRefreshToken("   ");
        CountingProbe probe = new CountingProbe("marketplaceParticipations=1", null);
        SpApiConnectivityPreflight preflight = build(credential, RecordingHttpTransport.json(LWA_OK), probe);

        PreflightReport report = preflight.check(credential.getShopId());

        assertFalse(report.ready());
        PreflightStage stage = report.stages().get(0);
        assertEquals(SpApiConnectivityPreflight.CODE_CREDENTIAL_INVALID, stage.errorCode());
        assertTrue(stage.detail().contains("refreshToken"));
        assertFalse(report.toString().contains(TestCredentials.FAKE_CLIENT_SECRET),
                "报告严禁回显 clientSecret");
        assertEquals(0, probe.calls);
    }

    @Test
    @DisplayName("LWA 401：LWA 段失败并给出重新授权动作，且不打平台")
    void lwaAuthFailureStopsBeforePlatform() {
        ShopCredential credential = TestCredentials.northAmerica();
        CountingProbe probe = new CountingProbe("marketplaceParticipations=1", null);
        RecordingHttpTransport transport = RecordingHttpTransport.of(request ->
                new RecordingHttpTransport.Reply(401,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"token revoked\"}"));
        SpApiConnectivityPreflight preflight = build(credential, transport, probe);

        PreflightReport report = preflight.check(credential.getShopId(), true);

        assertFalse(report.ready());
        assertEquals(3, report.stages().size());
        assertEquals(PreflightReport.STAGE_LWA, report.stages().get(1).name());
        assertEquals("LWA_AUTH_FAILED", report.stages().get(1).errorCode());
        assertTrue(report.stages().get(1).remediation().contains("重新授权"),
                "401 必须翻译成重新授权，实际=" + report.stages().get(1).remediation());
        assertEquals(PreflightStageStatus.SKIP, report.stages().get(2).status(),
                "LWA 失败后不得再打平台");
        assertNotNull(report.stages().get(2).remediation(), "SKIP 必须写明为什么没跑");
        assertEquals(0, probe.calls, "LWA 失败时禁止出网打平台");
    }

    @Test
    @DisplayName("LWA 429：分类为限流，不误报成凭证失效")
    void lwaRateLimitedIsClassified() {
        ShopCredential credential = TestCredentials.northAmerica();
        RecordingHttpTransport transport = RecordingHttpTransport.of(request ->
                new RecordingHttpTransport.Reply(429, "{\"error\":\"too_many_requests\"}"));
        SpApiConnectivityPreflight preflight =
                build(credential, transport, new CountingProbe("x", null));

        PreflightReport report = preflight.check(credential.getShopId(), true);

        assertEquals("LWA_RATE_LIMITED", report.stages().get(1).errorCode());
        assertTrue(report.stages().get(1).remediation().contains("429"));
        assertFalse(report.stages().get(1).remediation().contains("重新授权"),
                "限流不得被误报成凭证失效（否则运维会白白重做授权）");
    }

    @Test
    @DisplayName("只读调用 403：归因为授权/角色/RDT 三类，并保留平台状态码")
    void readApi403MapsToPermissionRemediation() {
        ShopCredential credential = TestCredentials.northAmerica();
        CountingProbe probe = new CountingProbe(null,
                SpApiCallException.statusFailure(OP, PATH, 403));
        SpApiConnectivityPreflight preflight = build(credential, RecordingHttpTransport.json(LWA_OK), probe);

        PreflightReport report = preflight.check(credential.getShopId());

        assertFalse(report.ready());
        PreflightStage stage = report.stages().get(2);
        assertEquals("READ_API_403", stage.errorCode());
        assertEquals(403, stage.platformStatus());
        assertTrue(stage.remediation().contains("RDT"), "403  remediation 必须点出 RDT 这一类原因");
        assertTrue(stage.remediation().contains("授权"));
    }

    @Test
    @DisplayName("只读调用传输失败：归因为出网/端点，且不伪造平台状态码")
    void readApiTransportFailureMapsToNetwork() {
        ShopCredential credential = TestCredentials.northAmerica();
        CountingProbe probe = new CountingProbe(null,
                SpApiCallException.transportFailure(OP, PATH, "Unable to establish loopback connection"));
        SpApiConnectivityPreflight preflight = build(credential, RecordingHttpTransport.json(LWA_OK), probe);

        PreflightReport report = preflight.check(credential.getShopId());

        PreflightStage stage = report.stages().get(2);
        assertEquals("READ_API_TRANSPORT", stage.errorCode());
        assertNull(stage.platformStatus(), "传输失败没有 HTTP 状态码，不得伪造 0 或 -1 之外的值");
        assertTrue(stage.remediation().contains("出网"));
    }

    @Test
    @DisplayName("未装配只读探测：记 SKIP 且 ready=false，明确写出是进程未启用真实链路")
    void unassembledProbeIsSkippedNotPassed() {
        ShopCredential credential = TestCredentials.northAmerica();
        SpApiConnectivityPreflight preflight =
                build(credential, RecordingHttpTransport.json(LWA_OK), null);

        PreflightReport report = preflight.check(credential.getShopId());

        assertFalse(report.ready(), "存在 SKIP 时不得 ready=true（否则等于把「没跑」读成「没问题」）");
        assertEquals(PreflightStageStatus.SKIP, report.stages().get(2).status());
        assertTrue(report.stages().get(2).remediation().contains("mock"),
                "必须说明是 profile/Bean 装配问题，而不是平台连不上");
    }

    @Test
    @DisplayName("未登记 marketplace：单独分类，不与 403/404 混为一谈")
    void unknownMarketplaceIsClassified() {
        ShopCredential credential = TestCredentials.northAmerica();
        CountingProbe probe = new CountingProbe(null, new UnknownMarketplaceException(
                UnknownMarketplaceException.CODE_UNKNOWN_MARKETPLACE, "unknown marketplace", "A1B2C3", "NA"));
        SpApiConnectivityPreflight preflight = build(credential, RecordingHttpTransport.json(LWA_OK), probe);

        PreflightReport report = preflight.check(credential.getShopId());

        assertEquals(SpApiConnectivityPreflight.CODE_MARKETPLACE_UNKNOWN, report.stages().get(2).errorCode());
        assertTrue(report.stages().get(2).remediation().contains("MarketplaceRegistry"));
    }

    @Test
    @DisplayName("报告与阶段文本不回显 refresh_token / client_secret / access_token")
    void reportNeverEchoesSecrets() {
        ShopCredential credential = TestCredentials.withAwsKeys(TestCredentials.northAmerica());
        CountingProbe probe = new CountingProbe("marketplaceParticipations=1", null);
        SpApiConnectivityPreflight preflight = build(credential, RecordingHttpTransport.json(LWA_OK), probe);

        PreflightReport ok = preflight.check(credential.getShopId());
        String okText = ok.toString();
        assertFalse(okText.contains(credential.getRefreshToken()), "refresh_token 严禁进报告");
        assertFalse(okText.contains(TestCredentials.FAKE_CLIENT_SECRET), "client_secret 严禁进报告");
        assertFalse(okText.contains("Atza|"), "access_token 严禁进报告");

        RecordingHttpTransport failing = RecordingHttpTransport.of(request ->
                new RecordingHttpTransport.Reply(401, "{\"error\":\"invalid_grant\"}"));
        PreflightReport ko = build(credential, failing, probe).check(credential.getShopId(), true);
        String koText = ko.toString();
        assertFalse(koText.contains(credential.getRefreshToken()));
        assertFalse(koText.contains(TestCredentials.FAKE_CLIENT_SECRET));
        assertFalse(koText.contains("Atza|"));
    }

    private static SpApiConnectivityPreflight build(ShopCredential credential,
                                                    RecordingHttpTransport transport,
                                                    ConnectivityProbe probe) {
        StubCredentialStore store = new StubCredentialStore();
        if (credential != null) {
            store.with(credential);
        }
        SpApiEndpointResolver resolver = SpApiEndpointResolver.officialOnly();
        LwaTokenManager tokens = new LwaTokenManager(transport, new SpApiConfig(), resolver);
        return new SpApiConnectivityPreflight(store, tokens, probe, resolver);
    }

    /** 记录调用次数并按预置结果响应/抛错的只读探测桩。 */
    private static final class CountingProbe implements ConnectivityProbe {

        private final String summary;
        private final RuntimeException failure;
        private int calls;

        CountingProbe(String summary, RuntimeException failure) {
            this.summary = summary;
            this.failure = failure;
        }

        @Override
        public String probe(Long shopId, String marketplaceId) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return summary;
        }
    }
}
