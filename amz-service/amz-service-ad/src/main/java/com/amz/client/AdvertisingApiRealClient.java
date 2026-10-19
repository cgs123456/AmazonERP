package com.amz.client;

import com.amz.credential.AdvertisingCredential;
import com.amz.credential.AdvertisingCredentialProvider;
import com.amz.model.AdCampaign;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import static com.amz.client.AdvertisingJsonSupport.array;
import static com.amz.client.AdvertisingJsonSupport.arrayOrEmpty;
import static com.amz.client.AdvertisingJsonSupport.decodeGzipIfNeeded;
import static com.amz.client.AdvertisingJsonSupport.decimalValue;
import static com.amz.client.AdvertisingJsonSupport.encode;
import static com.amz.client.AdvertisingJsonSupport.errorDetail;
import static com.amz.client.AdvertisingJsonSupport.hasText;
import static com.amz.client.AdvertisingJsonSupport.intValue;
import static com.amz.client.AdvertisingJsonSupport.invalidResponse;
import static com.amz.client.AdvertisingJsonSupport.longValue;
import static com.amz.client.AdvertisingJsonSupport.requiredLong;
import static com.amz.client.AdvertisingJsonSupport.requiredString;
import static com.amz.client.AdvertisingJsonSupport.sha256Hex;
import static com.amz.client.AdvertisingJsonSupport.sleep;
import static com.amz.client.AdvertisingJsonSupport.stringValue;
import static com.amz.client.AdvertisingJsonSupport.stripTrailingSlash;
import static com.amz.client.AdvertisingJsonSupport.truncate;

/**
 * Amazon Advertising API v3 真实客户端。
 * <p>
 * 凭证、profile 和 token 缓存均按 shopId 隔离；报表使用 v3 异步创建、轮询和下载流程；
 * 缺少凭证或上游失败时 fail-closed，显式抛出 {@link AdvertisingApiException}。
 */
@Slf4j
@Component
@Profile("!mock")
public class AdvertisingApiRealClient implements AdvertisingApiClient {

    private static final String CAMPAIGN_MEDIA_TYPE = "application/vnd.spCampaign.v3+json";
    private static final String KEYWORD_MEDIA_TYPE = "application/vnd.spKeyword.v3+json";
    private static final String REPORT_CREATE_CONTENT_TYPE = "application/vnd.createasyncreportrequest.v3+json";
    private static final String REPORT_CREATE_ACCEPT = "application/vnd.createasyncreportresponse.v3+json";
    private static final String REPORT_STATUS_ACCEPT = "application/vnd.getasyncreportresponse.v3+json";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_REPORT_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_REPORT_MAX_POLLS = 120;

    private final AdvertisingCredentialProvider credentialProvider;
    /**
     * HTTP 客户端，<b>惰性创建</b>（默认构造器路径）。
     * <p>
     * 本类是 {@code @Component @Profile("!mock")}，即<b>生产档启动时必然实例化</b>。
     * 若在构造器里 {@code HttpClient.newBuilder().build()}，那么任何
     * 「JVM 建不出 selector」的环境都会让整个 ad 服务起不来——而广告调用是可失败降级的
     * 业务能力，不该决定进程能否启动（实测：本机 Windows AF_UNIX loopback 被拒，
     * 修前 ad 冒烟崩在 {@code AdvertisingApiRealClient} 构造器）。
     * <p>
     * 注入 HttpClient 的测试构造器行为不变：它拿到的实例直接使用，不经过本方法。
     */
    private volatile HttpClient httpClient;
    private final Gson gson;
    private final Duration reportPollInterval;
    private final int reportMaxPolls;
    private final ConcurrentHashMap<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> tokenLocks = new ConcurrentHashMap<>();

    /**
     * 生产档构造器：<b>不在构造期创建 HttpClient</b>。
     * <p>
     * 传 {@code null} 表示「首次真正发请求时再建」（见 {@link #httpClient()}）。
     * 这样 Bean 能否被实例化不再取决于运行环境的网络栈。
     */
    @Autowired
    public AdvertisingApiRealClient(AdvertisingCredentialProvider credentialProvider) {
        this(credentialProvider, null, new Gson(),
                DEFAULT_REPORT_POLL_INTERVAL, DEFAULT_REPORT_MAX_POLLS);
    }

    public AdvertisingApiRealClient(AdvertisingCredentialProvider credentialProvider, HttpClient httpClient, Gson gson) {
        this(credentialProvider, httpClient, gson, DEFAULT_REPORT_POLL_INTERVAL, DEFAULT_REPORT_MAX_POLLS);
    }

    /**
     * 完整构造器。
     * <p>
     * {@code httpClient} 允许为 {@code null}，<b>但只有生产构造器会这么传</b>
     * （表示「首次发请求时再建」，见 {@link #httpClient()}）。公开构造器仍要求非 null：
     * 测试与调用方显式注入时不该悄悄退回真实网络，那是测试最容易失去隔离的地方。
     */
    public AdvertisingApiRealClient(AdvertisingCredentialProvider credentialProvider, HttpClient httpClient, Gson gson,
                                    Duration reportPollInterval, int reportMaxPolls) {
        if (credentialProvider == null || gson == null) {
            throw new IllegalArgumentException("AdvertisingApiRealClient dependencies must not be null");
        }
        if (reportPollInterval == null || reportPollInterval.isNegative() || reportPollInterval.isZero()) {
            throw new IllegalArgumentException("reportPollInterval must be positive");
        }
        if (reportMaxPolls <= 0) {
            throw new IllegalArgumentException("reportMaxPolls must be positive");
        }
        this.credentialProvider = credentialProvider;
        this.httpClient = httpClient;
        this.gson = gson;
        this.reportPollInterval = reportPollInterval;
        this.reportMaxPolls = reportMaxPolls;
    }

    @Override
    public List<AdCampaign> listCampaigns(Long shopId) {
        RequestContext context = context(shopId);
        JsonObject response = executeJson(context, "POST", "/sp/campaigns/list",
                new JsonObject(), CAMPAIGN_MEDIA_TYPE);
        return parseCampaigns(shopId, response);
    }

    @Override
    public List<AdKeyword> listKeywords(Long shopId, String campaignId) {
        RequestContext context = context(shopId);
        JsonObject body = new JsonObject();
        if (campaignId != null && !campaignId.isBlank()) {
            JsonArray campaignIds = new JsonArray();
            campaignIds.add(campaignId);
            body.add("campaignIdFilter", campaignIds);
        }
        JsonObject response = executeJson(context, "POST", "/sp/keywords/list", body, KEYWORD_MEDIA_TYPE);
        return parseKeywords(shopId, response);
    }

    @Override
    public boolean updateKeywordBid(Long shopId, Long keywordId, BigDecimal newBid) {
        RequestContext context = context(shopId);
        if (keywordId == null) {
            throw new AdvertisingApiException("AD_KEYWORD_ID_REQUIRED", "keywordId is required", null, false);
        }
        if (newBid == null || newBid.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AdvertisingApiException("AD_INVALID_BID", "newBid must be positive", null, false);
        }
        JsonObject keyword = new JsonObject();
        keyword.addProperty("keywordId", String.valueOf(keywordId));
        keyword.addProperty("bid", newBid);
        JsonArray keywords = new JsonArray();
        keywords.add(keyword);
        JsonObject body = new JsonObject();
        body.add("keywords", keywords);

        JsonObject response = executeJson(context, "PUT", "/sp/keywords", body, KEYWORD_MEDIA_TYPE);
        return parseMutationResult(response, "keywords");
    }

    @Override
    public List<AdReport> getReports(Long shopId, String startDate, String endDate) {
        RequestContext context = context(shopId);
        if (!hasText(startDate) || !hasText(endDate)) {
            throw new AdvertisingApiException("AD_REPORT_DATE_REQUIRED",
                    "startDate and endDate are required", null, false);
        }

        JsonObject configuration = new JsonObject();
        configuration.addProperty("adProduct", "SPONSORED_PRODUCTS");
        JsonArray groupBy = new JsonArray();
        groupBy.add("campaign");
        groupBy.add("adGroup");
        configuration.add("groupBy", groupBy);
        JsonArray columns = new JsonArray();
        columns.add("adGroupId");
        columns.add("campaignId");
        columns.add("impressions");
        columns.add("clicks");
        columns.add("cost");
        columns.add("purchases7d");
        columns.add("sales7d");
        columns.add("unitsSoldClicks7d");
        columns.add("date");
        configuration.add("columns", columns);
        configuration.addProperty("reportTypeId", "spCampaigns");
        configuration.addProperty("timeUnit", "DAILY");
        configuration.addProperty("format", "GZIP_JSON");

        JsonObject body = new JsonObject();
        body.addProperty("name", "amz-erp-sp-campaign-" + startDate + "-" + endDate);
        body.addProperty("startDate", startDate);
        body.addProperty("endDate", endDate);
        body.add("configuration", configuration);

        Response created = executeRaw(context, "POST", "/reporting/reports", body, REPORT_CREATE_CONTENT_TYPE,
                REPORT_CREATE_ACCEPT);
        ensureSuccess("create report", created);
        String reportId = created.header("Location");
        if (hasText(reportId)) {
            int slash = reportId.lastIndexOf('/');
            if (slash >= 0) {
                reportId = reportId.substring(slash + 1);
            }
        }
        if (!hasText(reportId) && hasText(created.body())) {
            reportId = stringValue(parseObject(created.body(), "report creation response"), "reportId");
        }
        if (!hasText(reportId)) {
            throw invalidResponse("report creation response missing reportId/Location");
        }

        String pollPath = "/reporting/reports/" + reportId;
        for (int attempt = 0; attempt < reportMaxPolls; attempt++) {
            if (attempt > 0) {
                sleep(reportPollInterval);
            }
            Response poll = executeRaw(context, "GET", pollPath, null, null, REPORT_STATUS_ACCEPT);
            ensureSuccess("poll report", poll);
            JsonObject report = parseObject(poll.body(), "report status response");
            String status = stringValue(report, "status");
            if ("COMPLETED".equalsIgnoreCase(status)) {
                String url = stringValue(report, "url");
                if (!hasText(url)) {
                    throw invalidResponse("successful report response missing download url");
                }
                return downloadReport(url);
            }
            if ("FAILURE".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                String reason = stringValue(report, "failureReason");
                throw new AdvertisingApiException("AD_REPORT_FAILED",
                        "Advertising report failed" + (hasText(reason) ? ": " + reason : ""), null, false);
            }
            if (!"PENDING".equalsIgnoreCase(status) && !"PROCESSING".equalsIgnoreCase(status)) {
                throw invalidResponse("unknown report status: " + status);
            }
        }
        throw new AdvertisingApiException("AD_REPORT_TIMEOUT",
                "Advertising report did not finish within " + reportMaxPolls + " polls", null, true);
    }

    private List<AdCampaign> parseCampaigns(Long shopId, JsonObject response) {
        JsonArray rows = array(response, "campaigns");
        List<AdCampaign> campaigns = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            if (element == null || !element.isJsonObject()) {
                throw invalidResponse("campaigns contains a non-object row");
            }
            JsonObject row = element.getAsJsonObject();
            AdCampaign campaign = new AdCampaign();
            campaign.setShopId(shopId);
            campaign.setCampaignId(requiredString(row, "campaignId"));
            campaign.setName(stringValue(row, "name"));
            campaign.setState(stringValue(row, "state"));
            campaign.setCampaignType("SP");
            JsonElement budget = row.get("budget");
            if (budget != null && budget.isJsonObject()) {
                campaign.setDailyBudget(decimalValue(budget.getAsJsonObject().get("budget")));
            } else {
                campaign.setDailyBudget(decimalValue(budget));
            }
            JsonElement dynamicBidding = row.get("dynamicBidding");
            if (dynamicBidding != null && dynamicBidding.isJsonObject()) {
                campaign.setBiddingStrategy(stringValue(dynamicBidding.getAsJsonObject(), "strategy"));
            }
            campaigns.add(campaign);
        }
        return campaigns;
    }

    private List<AdKeyword> parseKeywords(Long shopId, JsonObject response) {
        JsonArray rows = array(response, "keywords");
        List<AdKeyword> keywords = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            if (element == null || !element.isJsonObject()) {
                throw invalidResponse("keywords contains a non-object row");
            }
            JsonObject row = element.getAsJsonObject();
            AdKeyword keyword = new AdKeyword();
            keyword.setShopId(shopId);
            keyword.setId(requiredLong(row, "keywordId"));
            keyword.setCampaignId(stringValue(row, "campaignId"));
            keyword.setKeyword(stringValue(row, "keywordText"));
            keyword.setMatchType(stringValue(row, "matchType"));
            keyword.setBid(decimalValue(row.get("bid")));
            keyword.setState(stringValue(row, "state"));
            keywords.add(keyword);
        }
        return keywords;
    }

    private boolean parseMutationResult(JsonObject response, String resource) {
        JsonElement envelope = response.get(resource);
        if (envelope == null || !envelope.isJsonObject()) {
            throw invalidResponse("mutation response missing " + resource + " envelope");
        }
        JsonObject result = envelope.getAsJsonObject();
        JsonArray errors = arrayOrEmpty(result, "error");
        if (!errors.isEmpty()) {
            throw new AdvertisingApiException("AD_API_ERROR",
                    "Advertising mutation rejected: " + errors, 207, false);
        }
        JsonArray success = arrayOrEmpty(result, "success");
        return !success.isEmpty();
    }

    private List<AdReport> downloadReport(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, application/gzip")
                .GET()
                .build();
        HttpResponse<byte[]> response = sendBytes(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusException("download report", response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8));
        }
        try {
            byte[] decoded = decodeGzipIfNeeded(response.body());
            JsonElement root = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonArray()) {
                throw invalidResponse("report download body is not a JSON array");
            }
            List<AdReport> reports = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    throw invalidResponse("report download contains a non-object row");
                }
                JsonObject row = element.getAsJsonObject();
                AdReport report = new AdReport();
                report.setCampaignId(requiredString(row, "campaignId"));
                report.setAdGroupId(stringValue(row, "adGroupId"));
                report.setReportDate(java.time.LocalDate.parse(requiredString(row, "date")));
                report.setKeyword(stringValue(row, "keyword"));
                report.setImpressions(longValue(row.get("impressions")));
                report.setClicks(longValue(row.get("clicks")));
                report.setCost(decimalValue(row.get("cost")));
                report.setSales(decimalValue(row.get("sales7d")));
                report.setOrders(intValue(row.get("purchases7d")));
                report.setUnits(intValue(row.get("unitsSoldClicks7d")));
                reports.add(report);
            }
            return reports;
        } catch (AdvertisingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AdvertisingApiException("AD_INVALID_RESPONSE",
                    "Failed to parse Advertising report download", null, false, e);
        }
    }

    private JsonObject executeJson(RequestContext context, String method, String path, JsonObject body,
                                   String mediaType) {
        Response response = executeRaw(context, method, path, body, mediaType, mediaType);
        ensureSuccess(method + " " + path, response);
        return parseObject(response.body(), "Advertising API response");
    }

    private Response executeRaw(RequestContext context, String method, String path, JsonObject body,
                                String contentType, String accept) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken(context, attempt > 0);
            HttpResponse<String> response = sendString(context, method, path, body, contentType, accept, token);
            if (response.statusCode() == 401 && attempt == 0) {
                invalidateToken(context);
                continue;
            }
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Location").orElse(null));
        }
        throw new AdvertisingApiException("AD_AUTH_FAILED",
                "Advertising API authentication failed after token refresh", 401, false);
    }

    private HttpResponse<String> sendString(RequestContext context, String method, String path, JsonObject body,
                                            String contentType, String accept, String token) {
        HttpRequest.Builder builder = baseAdsRequest(context, method, path, contentType, accept, token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
        }
        try {
            return httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdvertisingApiException("AD_TRANSPORT_ERROR", "Advertising API request interrupted",
                    null, true, e);
        } catch (IOException e) {
            throw new AdvertisingApiException("AD_TRANSPORT_ERROR", "Advertising API transport failed",
                    null, true, e);
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) {
        try {
            return httpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdvertisingApiException("AD_TRANSPORT_ERROR", "Report download interrupted", null, true, e);
        } catch (IOException e) {
            throw new AdvertisingApiException("AD_TRANSPORT_ERROR", "Report download transport failed", null, true, e);
        }
    }

    private HttpRequest.Builder baseAdsRequest(RequestContext context, String method, String path, String contentType,
                                               String accept, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolveUri(context, path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Amazon-Advertising-API-Scope", context.credential().getProfileId())
                .header("Amazon-Advertising-API-ClientId", context.credential().getClientId());
        if (hasText(contentType)) {
            builder.header("Content-Type", contentType);
        }
        if (hasText(accept)) {
            builder.header("Accept", accept);
        }
        return builder;
    }

    private URI resolveUri(RequestContext context, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return URI.create(path);
        }
        String endpoint = stripTrailingSlash(context.credential().getEndpoint());
        return URI.create(endpoint + (path.startsWith("/") ? path : "/" + path));
    }

    private String accessToken(RequestContext context, boolean forceRefresh) {
        String cacheKey = tokenCacheKey(context);
        if (!forceRefresh) {
            TokenEntry cached = tokenCache.get(cacheKey);
            if (cached != null && cached.expiresAt.isAfter(Instant.now().plusSeconds(30))) {
                return cached.accessToken;
            }
        }
        Object lock = tokenLocks.computeIfAbsent(cacheKey, ignored -> new Object());
        synchronized (lock) {
            TokenEntry cached = tokenCache.get(cacheKey);
            if (!forceRefresh && cached != null && cached.expiresAt.isAfter(Instant.now().plusSeconds(30))) {
                return cached.accessToken;
            }
            TokenEntry refreshed = exchangeRefreshToken(context);
            tokenCache.put(cacheKey, refreshed);
            return refreshed.accessToken;
        }
    }

    private TokenEntry exchangeRefreshToken(RequestContext context) {
        AdvertisingCredential credential = context.credential();
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + encode(credential.getRefreshToken())
                + "&client_id=" + encode(credential.getClientId())
                + "&client_secret=" + encode(credential.getClientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(credential.getTokenEndpoint()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdvertisingApiException("AD_TRANSPORT_ERROR", "LWA token request interrupted",
                    null, true, e);
        } catch (IOException e) {
            throw new AdvertisingApiException("AD_TRANSPORT_ERROR", "LWA token request failed",
                    null, true, e);
        }
        if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403) {
            throw new AdvertisingApiException("AD_AUTH_FAILED", "LWA refresh token rejected",
                    response.statusCode(), false);
        }
        if (response.statusCode() == 429) {
            throw new AdvertisingApiException("AD_RATE_LIMITED", "LWA token endpoint rate limited",
                    response.statusCode(), true);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            boolean retryable = response.statusCode() >= 500;
            throw new AdvertisingApiException(retryable ? "AD_UPSTREAM_ERROR" : "AD_API_ERROR",
                    "LWA token endpoint returned HTTP " + response.statusCode(),
                    response.statusCode(), retryable);
        }
        JsonObject token = parseObject(response.body(), "LWA token response");
        String accessToken = stringValue(token, "access_token");
        JsonElement expiresIn = token.get("expires_in");
        if (!hasText(accessToken) || expiresIn == null || !expiresIn.isJsonPrimitive()
                || !expiresIn.getAsJsonPrimitive().isNumber()) {
            throw invalidResponse("LWA token response missing access_token/expires_in");
        }
        long seconds;
        try {
            seconds = expiresIn.getAsLong();
        } catch (RuntimeException e) {
            throw invalidResponse("LWA token response contains invalid expires_in");
        }
        if (seconds <= 0) {
            throw invalidResponse("LWA token response contains non-positive expires_in");
        }
        return new TokenEntry(accessToken, Instant.now().plusSeconds(seconds));
    }

    private void invalidateToken(RequestContext context) {
        tokenCache.remove(tokenCacheKey(context));
    }

    private String tokenCacheKey(RequestContext context) {
        AdvertisingCredential credential = context.credential();
        String fingerprint = credential.getClientId() + "\0" + credential.getClientSecret() + "\0"
                + credential.getRefreshToken() + "\0" + credential.getTokenEndpoint();
        return context.shopId() + ":" + sha256Hex(fingerprint);
    }

    private RequestContext context(Long shopId) {
        if (shopId == null) {
            throw new AdvertisingApiException("AD_SHOP_ID_REQUIRED", "shopId is required", null, false);
        }
        AdvertisingCredential credential = credentialProvider.get(shopId).orElse(null);
        if (credential == null || !credential.isComplete()) {
            throw new AdvertisingApiException("AD_CREDENTIALS_NOT_CONFIGURED",
                    "Advertising credentials are not configured for shopId=" + shopId, null, false);
        }
        if (!shopId.equals(credential.getShopId())) {
            throw new AdvertisingApiException("AD_CREDENTIAL_SHOP_MISMATCH",
                    "Credential shopId does not match requested shopId", null, false);
        }
        return new RequestContext(shopId, credential);
    }

    private void ensureSuccess(String operation, Response response) {
        if (response.status() >= 200 && response.status() < 300) {
            return;
        }
        throw statusException(operation, response.status(), response.body());
    }

    private AdvertisingApiException statusException(String operation, int status, String body) {
        String detail = errorDetail(body);
        if (status == 401 || status == 403) {
            return new AdvertisingApiException("AD_AUTH_FAILED",
                    operation + " failed with HTTP " + status + detail, status, false);
        }
        if (status == 429) {
            return new AdvertisingApiException("AD_RATE_LIMITED",
                    operation + " rate limited" + detail, status, true);
        }
        if (status >= 500) {
            return new AdvertisingApiException("AD_UPSTREAM_ERROR",
                    operation + " failed with upstream HTTP " + status + detail, status, true);
        }
        return new AdvertisingApiException("AD_API_ERROR",
                operation + " failed with HTTP " + status + detail, status, false);
    }

    private JsonObject parseObject(String body, String description) {
        if (!hasText(body)) {
            throw invalidResponse(description + " is empty");
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) {
                throw invalidResponse(description + " is not a JSON object");
            }
            return root.getAsJsonObject();
        } catch (AdvertisingApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AdvertisingApiException("AD_INVALID_RESPONSE",
                    description + " is invalid JSON", null, false, e);
        }
    }

    /**
     * 首次真正发请求时才创建客户端（双重检查，线程安全）。
     * <p>
     * 见字段注释：构造期建 selector 会让「环境是否支持网络」决定 ad 服务能否启动。
     */
    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    private record RequestContext(Long shopId, AdvertisingCredential credential) {
    }

    private record Response(int status, String body, String locationHeader) {
        String header(String name) {
            return "Location".equalsIgnoreCase(name) ? locationHeader : null;
        }
    }

    private static final class TokenEntry {
        private final String accessToken;
        private final Instant expiresAt;

        private TokenEntry(String accessToken, Instant expiresAt) {
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }
    }
}
