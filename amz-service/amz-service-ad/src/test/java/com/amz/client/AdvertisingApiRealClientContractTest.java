package com.amz.client;

import com.amz.credential.AdvertisingCredential;
import com.amz.credential.AdvertisingCredentialProvider;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AdvertisingApiRealClient 真实 Ads API v3 契约")
class AdvertisingApiRealClientContractTest {

    private final Gson gson = new Gson();
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    @DisplayName("shopId 缺失时在发起网络请求前失败")
    void rejectsMissingShopIdBeforeNetwork() {
        AdvertisingApiRealClient client = client(shopId -> Optional.empty());

        AdvertisingApiException ex = assertThrows(AdvertisingApiException.class,
                () -> client.listCampaigns(null));

        assertEquals("AD_SHOP_ID_REQUIRED", ex.getCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    @DisplayName("缺少店铺凭证时 fail-closed，不返回空列表")
    void failsClosedWhenCredentialsMissing() {
        AdvertisingApiRealClient client = client(shopId -> Optional.empty());

        AdvertisingApiException ex = assertThrows(AdvertisingApiException.class,
                () -> client.listCampaigns(42L));

        assertEquals("AD_CREDENTIALS_NOT_CONFIGURED", ex.getCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    @DisplayName("列表活动使用 v3 POST 契约、店铺 profile 与店铺 token")
    void listCampaignsUsesV3ContractAndShopIsolation() throws Exception {
        AtomicReference<RecordedRequest> tokenRequest = new AtomicReference<>();
        List<RecordedRequest> adsRequests = new CopyOnWriteArrayList<>();
        HttpServer server = start(exchange -> {
            RecordedRequest request = record(exchange);
            if ("/auth/o2/token".equals(exchange.getRequestURI().getPath())) {
                tokenRequest.set(request);
                respond(exchange, 200, "{\"access_token\":\"token-shop-a\",\"expires_in\":3600}", "application/json");
                return;
            }
            adsRequests.add(request);
            respond(exchange, 200, "{\"campaigns\":[{\"campaignId\":\"c1\",\"name\":\"Campaign A\","
                    + "\"state\":\"ENABLED\",\"budget\":{\"budget\":50.0},\"targetingType\":\"MANUAL\"}],"
                    + "\"totalResults\":1}", "application/json");
        });
        String base = baseUrl(server);
        AdvertisingApiRealClient client = client(shopId -> Optional.of(credential(base, "client-shop-a", "profile-a", "refresh-a")));

        var campaigns = client.listCampaigns(101L);

        assertEquals(1, campaigns.size());
        assertEquals("c1", campaigns.get(0).getCampaignId());
        assertEquals("Campaign A", campaigns.get(0).getName());
        assertEquals(new BigDecimal("50.0"), campaigns.get(0).getDailyBudget());
        assertEquals(101L, campaigns.get(0).getShopId());
        assertEquals("POST", adsRequests.get(0).method());
        assertEquals("/sp/campaigns/list", adsRequests.get(0).path());
        assertEquals("Bearer token-shop-a", adsRequests.get(0).header("Authorization"));
        assertEquals("profile-a", adsRequests.get(0).header("Amazon-Advertising-API-Scope"));
        assertEquals("client-shop-a", adsRequests.get(0).header("Amazon-Advertising-API-ClientId"));
        assertEquals("application/vnd.spCampaign.v3+json", adsRequests.get(0).header("Content-Type"));
        assertTrue(tokenRequest.get().body().contains("refresh_token=refresh-a"));
    }

    @Test
    @DisplayName("列表关键词使用 v3 POST 契约并解析响应信封")
    void listKeywordsUsesV3ListEnvelope() throws Exception {
        List<RecordedRequest> adsRequests = new CopyOnWriteArrayList<>();
        HttpServer server = start(exchange -> {
            if ("/auth/o2/token".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"access_token\":\"token-a\",\"expires_in\":3600}", "application/json");
                return;
            }
            adsRequests.add(record(exchange));
            respond(exchange, 200, "{\"keywords\":[{\"keywordId\":\"123\",\"campaignId\":\"c1\","
                    + "\"keywordText\":\"earbuds\",\"matchType\":\"EXACT\",\"bid\":1.23,\"state\":\"ENABLED\"}],"
                    + "\"totalResults\":1}", "application/vnd.spKeyword.v3+json");
        });
        String base = baseUrl(server);
        AdvertisingApiRealClient client = client(shopId -> Optional.of(credential(base, "client-shop-a", "profile-a", "refresh-a")));

        var keywords = client.listKeywords(101L, "c1");

        assertEquals(1, keywords.size());
        assertEquals(123L, keywords.get(0).getId());
        assertEquals("c1", keywords.get(0).getCampaignId());
        assertEquals("earbuds", keywords.get(0).getKeyword());
        assertEquals(new BigDecimal("1.23"), keywords.get(0).getBid());
        assertEquals("/sp/keywords/list", adsRequests.get(0).path());
        assertTrue(adsRequests.get(0).body().contains("\"campaignIdFilter\""));
        assertTrue(adsRequests.get(0).body().contains("\"c1\""));
    }

    @Test
    @DisplayName("更新竞价使用店铺作用域的 PUT /sp/keywords，且不改变暂停状态")
    void updateKeywordBidUsesShopScopedV3Mutation() throws Exception {
        List<RecordedRequest> adsRequests = new CopyOnWriteArrayList<>();
        HttpServer server = start(exchange -> {
            if ("/auth/o2/token".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"access_token\":\"token-a\",\"expires_in\":3600}", "application/json");
                return;
            }
            adsRequests.add(record(exchange));
            respond(exchange, 207, "{\"keywords\":{\"success\":[{\"index\":0,\"keywordId\":\"123\"}],\"error\":[]}}",
                    "application/vnd.spKeyword.v3+json");
        });
        String base = baseUrl(server);
        AdvertisingApiRealClient client = client(shopId -> Optional.of(credential(base, "client-shop-a", "profile-a", "refresh-a")));

        boolean updated = client.updateKeywordBid(101L, 123L, new BigDecimal("1.25"));

        assertTrue(updated);
        assertEquals("PUT", adsRequests.get(0).method());
        assertEquals("/sp/keywords", adsRequests.get(0).path());
        JsonObject body = gson.fromJson(adsRequests.get(0).body(), JsonObject.class);
        JsonObject keyword = body.getAsJsonArray("keywords").get(0).getAsJsonObject();
        assertEquals("123", keyword.get("keywordId").getAsString());
        assertEquals(1.25, keyword.get("bid").getAsDouble());
        assertFalse(keyword.has("state"), "仅调价不应顺带把 PAUSED 改成 ENABLED");
    }

    @Test
    @DisplayName("上游 401/403 映射为可识别认证异常，不降级为空数据")
    void mapsAuthFailureToTypedException() throws Exception {
        HttpServer server = start(exchange -> {
            if ("/auth/o2/token".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"access_token\":\"token-a\",\"expires_in\":3600}", "application/json");
                return;
            }
            respond(exchange, 403, "{\"errors\":[{\"code\":\"FORBIDDEN\",\"message\":\"denied\"}]}", "application/json");
        });
        String base = baseUrl(server);
        AdvertisingApiRealClient client = client(shopId -> Optional.of(credential(base, "client-shop-a", "profile-a", "refresh-a")));

        AdvertisingApiException ex = assertThrows(AdvertisingApiException.class, () -> client.listCampaigns(101L));

        assertEquals("AD_AUTH_FAILED", ex.getCode());
        assertEquals(403, ex.getStatusCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    @DisplayName("报表使用 v3 异步创建、轮询并下载 gzip JSON")
    void getReportsUsesAsyncV3Contract() throws Exception {
        List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = start(exchange -> {
            if ("/auth/o2/token".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"access_token\":\"token-a\",\"expires_in\":3600}", "application/json");
                return;
            }
            RecordedRequest request = record(exchange);
            requests.add(request);
            if ("/reporting/reports".equals(request.path())) {
                exchange.getResponseHeaders().set("Location", "/reporting/reports/r1");
                respond(exchange, 202, "", "application/json");
                return;
            }
            if ("/reporting/reports/r1".equals(request.path())) {
                if (polls.getAndIncrement() == 0) {
                    respond(exchange, 200, "{\"reportId\":\"r1\",\"status\":\"PENDING\"}",
                            "application/vnd.getasyncreportresponse.v3+json");
                } else {
                    String download = "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/download/r1.gz";
                    respond(exchange, 200,
                            "{\"reportId\":\"r1\",\"status\":\"COMPLETED\",\"url\":\"" + download + "\"}",
                            "application/vnd.getasyncreportresponse.v3+json");
                }
                return;
            }
            if ("/download/r1.gz".equals(request.path())) {
                respondBytes(exchange, 200, gzip("[{\"date\":\"2026-09-01\",\"campaignId\":\"c1\","
                        + "\"adGroupId\":\"ag1\",\"impressions\":100,\"clicks\":5,\"cost\":1.25,"
                        + "\"purchases7d\":2,\"sales7d\":10.0,\"unitsSoldClicks7d\":3}]"),
                        "application/gzip");
                return;
            }
            respond(exchange, 404, "{}", "application/json");
        });
        String base = baseUrl(server);
        AdvertisingApiRealClient client = client(shopId -> Optional.of(credential(base, "client-shop-a", "profile-a", "refresh-a")));

        var reports = client.getReports(101L, "2026-09-01", "2026-09-01");

        assertEquals(1, reports.size());
        assertEquals("c1", reports.get(0).getCampaignId());
        assertEquals("ag1", reports.get(0).getAdGroupId());
        assertEquals(java.time.LocalDate.of(2026, 9, 1), reports.get(0).getReportDate());
        assertEquals(100L, reports.get(0).getImpressions());
        assertEquals(5L, reports.get(0).getClicks());
        assertEquals(new BigDecimal("1.25"), reports.get(0).getCost());
        assertEquals(2, reports.get(0).getOrders());
        assertEquals(new BigDecimal("10.0"), reports.get(0).getSales());
        assertEquals(3, reports.get(0).getUnits());

        RecordedRequest create = requests.stream().filter(r -> "/reporting/reports".equals(r.path())).findFirst().orElseThrow();
        assertEquals("POST", create.method());
        assertEquals("application/vnd.createasyncreportrequest.v3+json", create.header("Content-Type"));
        JsonObject createBody = gson.fromJson(create.body(), JsonObject.class);
        assertEquals("2026-09-01", createBody.get("startDate").getAsString());
        assertEquals("2026-09-01", createBody.get("endDate").getAsString());
        JsonObject configuration = createBody.getAsJsonObject("configuration");
        assertEquals("spCampaigns", configuration.get("reportTypeId").getAsString());
        assertEquals("DAILY", configuration.get("timeUnit").getAsString());
        assertEquals(List.of("campaign", "adGroup"), strings(configuration.getAsJsonArray("groupBy")));
        List<String> columns = strings(configuration.getAsJsonArray("columns"));
        assertTrue(columns.containsAll(List.of("date", "adGroupId", "campaignId", "impressions", "clicks",
                "cost", "purchases7d", "sales7d", "unitsSoldClicks7d")), columns.toString());
        assertFalse(columns.contains("purchases"));
        assertFalse(columns.contains("sales"));

        List<RecordedRequest> pollRequests = requests.stream()
                .filter(r -> "/reporting/reports/r1".equals(r.path())).toList();
        assertEquals(2, pollRequests.size());
        assertTrue(pollRequests.stream().allMatch(r ->
                "application/vnd.getasyncreportresponse.v3+json".equals(r.header("Accept"))));
    }

    private static List<String> strings(com.google.gson.JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }

    private AdvertisingApiRealClient client(AdvertisingCredentialProvider provider) {
        return new AdvertisingApiRealClient(provider, HttpClient.newHttpClient(), gson, java.time.Duration.ofMillis(10), 10);
    }

    private static AdvertisingCredential credential(String base, String client, String profile, String refresh) {
        AdvertisingCredential credential = new AdvertisingCredential();
        credential.setShopId(101L);
        credential.setClientId(client);
        credential.setClientSecret("secret-" + client);
        credential.setRefreshToken(refresh);
        credential.setProfileId(profile);
        credential.setEndpoint(base);
        credential.setTokenEndpoint(base + "/auth/o2/token");
        return credential;
    }

    private HttpServer start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] gzip(String json) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static void respondBytes(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static RecordedRequest record(HttpExchange exchange) throws IOException {
        Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                        e -> String.join(",", e.getValue())));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body);
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record RecordedRequest(String method, String path, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }
}




