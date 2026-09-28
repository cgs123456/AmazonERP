package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.client.OrdersClient;
import com.amz.connector.ConnectorRegistry;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.outbox.SpApiCallOutboxService;
import com.amz.outbox.SpApiOutboxReplayExecutor;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.result.ApiError;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接器能力清单与自检端点（Task 6）。
 * <p>
 * <b>为什么需要：</b>「有没有对接能力」此前只能靠 README 与口头声明。凭证到位那天，
 * 运维需要一个<b>一条命令可判定</b>的事实源：这个连接器现在能不能调、缺什么、证据到哪一级。
 * 本端点把 {@link ConnectorRegistry} 的能力表与 {@link ConnectorEvidencePolicy} 的判定
 * 直接暴露成 JSON，前端「已对接 / 未接通」标签改为读它，不再由人写死。
 * <p>
 * <b>路径：</b>服务直连映射为 {@code /spapi/connectors}；网关另提供
 * {@code /api/connectors/** -> /spapi/connectors/**} 重写别名，供统一对外前缀使用。
 * 两条路径的响应契约一致，但网关别名仍须经真实 Nacos 服务发现做运行期验证。
 * <p>
 * <b>守卫：</b>每个端点都显式带 {@link RequireRole}——{@code ConnectorControllerGuardTest}
 * 用反射断言「新增无守卫端点」会直接失败，避免本类成为附录 F 无守卫清单的新增项。
 * 自检请求体里的 shopId 不能靠 {@code @ShopScoped}（切面只认 {@code @RequestParam}/
 * {@code @PathVariable}），因此显式调用 {@link UserContext#isShopAllowedStrict(Long)}。
 * <p>
 * <b>脱敏：</b>自检结果只回 operation、outcomeCode、耗时与<b>条数</b>；
 * 订单内容含 PII（买家姓名/地址），绝不回传；异常文本一律过 {@link ErrorSummary#redact}。
 */
@RestController
@RequestMapping("/spapi/connectors")
public class ConnectorController {

    private static final Logger log = LoggerFactory.getLogger(ConnectorController.class);

    /** 网关对外统一别名；常量名保留为历史兼容，服务直连路径仍是 {@code /spapi/connectors}。 */
    public static final String PLANNED_PUBLIC_PATH = "/api/connectors";

    /** 自检拉取订单的时间窗口（只读、越小越好，只为验证链路通）。 */
    private static final Duration SELF_TEST_WINDOW = Duration.ofHours(24);

    /** 自检使用的订单状态集合（与 {@code SpapiController} 的手工同步口径一致）。 */
    private static final List<String> SELF_TEST_STATUSES = List.of(
            "Unshipped", "PartiallyShipped", "Shipped", "Canceled");

    /** 平台状态码提取：{@code SpApiGateway} 的失败消息形如 {@code status=429}。 */
    private static final Pattern STATUS_IN_MESSAGE = Pattern.compile("status=(-?\\d+)");

    /** 凭证缺失的本地 outcomeCode（不是平台状态码，必须可区分）。 */
    public static final String OUTCOME_CREDENTIAL_MISSING = "CREDENTIAL_MISSING";

    /** 凭证缺 marketplaceId 的本地 outcomeCode。 */
    public static final String OUTCOME_MARKETPLACE_MISSING = "MARKETPLACE_MISSING";

    /** 无法从异常中识别平台状态码时的 outcomeCode。 */
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";

    private final ConnectorRegistry registry;
    private final ShopCredentialStore credentialStore;
    private final OrdersClient ordersClient;
    private final SpiRateLimiter rateLimiter;
    private final SpApiCallOutboxService outboxService;
    private final SpApiOutboxReplayExecutor replayExecutor;

    /** 兼容仅使用能力清单/限流/自检端点的既有测试与调用方。 */
    public ConnectorController(ConnectorRegistry registry,
                               ShopCredentialStore credentialStore,
                               OrdersClient ordersClient,
                               SpiRateLimiter rateLimiter) {
        this(registry, credentialStore, ordersClient, rateLimiter, null, null);
    }

    @Autowired
    public ConnectorController(ConnectorRegistry registry,
                               ShopCredentialStore credentialStore,
                               OrdersClient ordersClient,
                               SpiRateLimiter rateLimiter,
                               SpApiCallOutboxService outboxService,
                               SpApiOutboxReplayExecutor replayExecutor) {
        this.registry = registry;
        this.credentialStore = credentialStore;
        this.ordersClient = ordersClient;
        this.rateLimiter = rateLimiter;
        this.outboxService = outboxService;
        this.replayExecutor = replayExecutor;
    }

    /**
     * 全部连接器能力清单。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping
    public Result<List<ConnectorRegistry.Capability>> list() {
        return Result.success(registry.describeAll());
    }

    /**
     * 最近一次有效 SP-API 限流响应头观测。
     * <p>
     * 只读，不触发出网，也不把观测值放大到官方配额以上；无观测时返回空列表。该端点提供
     * P0-52b 所需的验收取证出口，真实 {@code x-amzn-RateLimit-Limit} 语义仍需沙箱/生产联调。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/rate-limits")
    public Result<List<SpiRateLimiter.RateLimitObservation>> rateLimits() {
        return Result.success(rateLimiter.observations());
    }

    /**
     * SP-API 持久化调用账本（不含请求/响应正文、查询串或密文）。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/outbox")
    public Result<List<SpApiCallOutboxService.OutboxView>> outbox(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        if (outboxService == null) {
            return Result.failure("SP-API Outbox 未启用",
                    ErrorSummary.localError(LocalApiException.CODE_CONNECTOR_NOT_FOUND));
        }
        if (!isAdmin() && !hasAuthorizedShops()) {
            log.warn("Outbox 列表越权拦截：用户无任何店铺授权，userId={}, role={}",
                    UserContext.getUserId(), UserContext.getRole());
            return forbidden("当前用户没有任何店铺授权，拒绝查询 Outbox");
        }
        try {
            // 只有签名 JWT 中的 ADMIN 才允许把空 shops 解释为全局管理范围；
            // 普通用户空 shops 必须 fail-closed，绝不能把 null 下推成全库查询。
            List<Long> allowedShopIds = isAdmin() ? null : UserContext.getShops();
            return Result.success(outboxService.list(status, limit, allowedShopIds));
        } catch (IllegalArgumentException e) {
            return Result.failure("Outbox 查询参数无效：" + ErrorSummary.redact(e.getMessage()),
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
    }

    /**
     * 人工重放单条 Outbox 记录；写操作只允许 OPERATOR/ADMIN 显式触发。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/outbox/{id}/replay")
    public Result<SpApiOutboxReplayExecutor.ReplayResult> replayOutbox(@PathVariable Long id) {
        if (outboxService == null || replayExecutor == null) {
            return Result.failure("SP-API Outbox 重放未启用",
                    ErrorSummary.localError(LocalApiException.CODE_CONNECTOR_NOT_FOUND));
        }
        if (!isAdmin() && !hasAuthorizedShops()) {
            log.warn("Outbox 重放越权拦截：用户无任何店铺授权，id={}, userId={}, role={}",
                    id, UserContext.getUserId(), UserContext.getRole());
            return forbidden("当前用户没有任何店铺授权，拒绝重放 Outbox");
        }
        SpApiCallOutboxService.OutboxView view;
        try {
            view = outboxService.view(id);
        } catch (RuntimeException e) {
            return Result.failure("Outbox 记录不存在：id=" + id,
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (!canAccessShop(view.shopId())) {
            log.warn("Outbox 重放越权拦截：id={}, shopId={}, userId={}",
                    id, view.shopId(), UserContext.getUserId());
            return forbidden("无权操作该店铺 shopId=" + view.shopId());
        }
        SpApiOutboxReplayExecutor.ReplayResult result = replayExecutor.replay(id, false);
        if (result.success()) {
            return Result.success(result);
        }
        return Result.failure("Outbox 重放未成功：" + result.outcome(),
                ErrorSummary.localError("OUTBOX_REPLAY_" + result.outcome()));
    }

    /**
     * 单个连接器能力清单。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{code}")
    public Result<ConnectorRegistry.Capability> one(@PathVariable String code) {
        ConnectorRegistry.Capability capability = registry.describe(code);
        if (capability == null) {
            return Result.failure("未知连接器：" + code + "（当前只有 " + ConnectorRegistry.SPAPI + "）",
                    ErrorSummary.localError(LocalApiException.CODE_CONNECTOR_NOT_FOUND));
        }
        return Result.success(capability);
    }

    /**
     * 执行一次真实只读自检（当前实现：{@code orders.getOrders}）。
     * <p>
     * <b>为什么不用计划原文的 marketplaceParticipations：</b>spec §1.4.1 实测
     * Sellers（marketplaceParticipations）的官方路径关键词在仓库命中 <b>0</b>，即该 API 根本没有实现。
     * 用未实现的 operation 做自检，等于每次都返回「未实现」而不是「连不上」，
     * 把「代码缺失」误报成「凭证/网络问题」。因此改用在册的只读 operation
     * {@code orders.getOrders}：它一次性串起 LWA 换 token、必填头、限流与路径契约。
     *
     * @param code 连接器标识
     * @param body 请求体（{@code shopId}），可为 null
     * @return 自检摘要；凭证缺失等前置失败也返回结构化 data，不返回 null
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{code}/self-test")
    public Result<Map<String, Object>> selfTest(@PathVariable String code,
                                                @RequestBody(required = false) SelfTestRequest body) {
        if (!ConnectorRegistry.SPAPI.equals(code)) {
            return Result.failure("未知连接器：" + code + "（当前只有 " + ConnectorRegistry.SPAPI + "）",
                    ErrorSummary.localError(LocalApiException.CODE_CONNECTOR_NOT_FOUND));
        }
        if (body == null || body.getShopId() == null) {
            return Result.failure("shopId 不能为空",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        Long shopId = body.getShopId();
        // @ShopScoped 切面不校验 @RequestBody 内嵌 shopId，此处必须显式校验（越权防护）。
        if (!canAccessShop(shopId)) {
            log.warn("连接器自检越权拦截：connector={}, shopId={}, userId={}", code, shopId, UserContext.getUserId());
            return Result.failure("无权操作该店铺 shopId=" + shopId,
                    ErrorSummary.localError(LocalApiException.CODE_FORBIDDEN));
        }

        ShopCredential credential = credentialStore.get(shopId);
        if (credential == null) {
            return finish(code, new ConnectorRegistry.SelfTestResult(
                    Instant.now(), false, "orders.getOrders", OUTCOME_CREDENTIAL_MISSING,
                    "no credential for shopId=" + shopId, 0L, 0),
                    "自检未执行：店铺 shopId=" + shopId + " 无凭证（A2 缺凭证必须显式失败，不得回空结果）",
                    ErrorSummary.localError("CREDENTIAL_MISSING"));
        }
        if (credential.getMarketplaceId() == null || credential.getMarketplaceId().isBlank()) {
            return finish(code, new ConnectorRegistry.SelfTestResult(
                    Instant.now(), false, "orders.getOrders", OUTCOME_MARKETPLACE_MISSING,
                    "marketplaceId missing for shopId=" + shopId, 0L, 0),
                    "自检未执行：店铺 shopId=" + shopId + " 的凭证缺 marketplaceId",
                    ErrorSummary.localError("MARKETPLACE_MISSING"));
        }

        Instant createdAfter = Instant.now().minus(SELF_TEST_WINDOW);
        long startedAt = System.nanoTime();
        try {
            List<JsonObject> orders = ordersClient.fetchOrders(
                    shopId, credential.getMarketplaceId(), createdAfter, SELF_TEST_STATUSES);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            int size = orders == null ? 0 : orders.size();
            return finish(code, new ConnectorRegistry.SelfTestResult(
                    Instant.now(), true, "orders.getOrders", "200",
                    "ok orders=" + size + " window=" + SELF_TEST_WINDOW, elapsedMs, size),
                    "自检成功（只读）：orders.getOrders 返回 " + size + " 条");
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            String detail = ErrorSummary.of(e);
            ApiError apiError = ErrorSummary.toApiError(e);
            String outcome = outcomeCode(apiError, detail);
            log.error("连接器自检失败：connector={}, shopId={}, outcome={}", code, shopId, outcome, e);
            return finish(code, new ConnectorRegistry.SelfTestResult(
                    Instant.now(), false, "orders.getOrders", outcome, detail, elapsedMs, 0),
                    "自检失败：" + outcome + " — " + detail, apiError);
        }
    }

    /**
     * 记录自检结果并组装响应（成功与失败都带结构化 data，方便运维直接用）。
     */
    private Result<Map<String, Object>> finish(String code,
                                               ConnectorRegistry.SelfTestResult result,
                                               String message) {
        return finish(code, result, message, null);
    }

    private Result<Map<String, Object>> finish(String code,
                                               ConnectorRegistry.SelfTestResult result,
                                               String message,
                                               ApiError error) {
        registry.recordSelfTest(code, result);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connector", code);
        data.put("operation", result.operation());
        data.put("ok", result.ok());
        data.put("outcomeCode", result.outcomeCode());
        data.put("detail", result.detail());
        data.put("elapsedMs", result.elapsedMs());
        data.put("itemCount", result.itemCount());
        data.put("at", result.at().toString());
        ConnectorRegistry.Capability capability = registry.describe(code);
        if (capability != null) {
            data.put("evidenceLevel", capability.evidenceLevel());
            data.put("displayText", capability.displayText());
        }
        if (error != null) {
            data.put("error", error);
        }
        return result.ok() ? Result.success(data) : new Result<>(message, 400, data, error);
    }

    /**
     * 优先读取类型化异常中的平台状态码，旧异常文本只作兼容回退。
     */
    static String outcomeCode(ApiError error, String detail) {
        if (error != null && error.getPlatformStatus() != null) {
            return String.valueOf(error.getPlatformStatus());
        }
        return outcomeCode(detail);
    }

    /** 兼容旧调用方的文本回退；新调用点必须优先使用类型化字段。 */
    static String outcomeCode(String detail) {
        if (detail == null) {
            return OUTCOME_UNKNOWN;
        }
        Matcher matcher = STATUS_IN_MESSAGE.matcher(detail);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (detail.contains("UnknownMarketplace")) {
            return OUTCOME_MARKETPLACE_MISSING;
        }
        return OUTCOME_UNKNOWN;
    }

    /**
     * 请求边界的严格店铺授权判断：ADMIN 之外必须携带非空授权店铺列表且命中目标店铺。
     * <p>
     * 不能复用 {@link UserContext#isShopAllowed(Long)} 的空列表放行语义：该方法还要兼容
     * 无 HTTP 上下文的内部调用，而 Controller 是外部信任边界，空 shops 必须 fail-closed。
     */
    private static boolean canAccessShop(Long shopId) {
        return UserContext.isShopAllowedStrict(shopId);
    }

    private static boolean hasAuthorizedShops() {
        List<Long> shops = UserContext.getShops();
        return shops != null && !shops.isEmpty();
    }

    private static boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(UserContext.getRole());
    }

    private static <T> Result<T> forbidden(String message) {
        return Result.failure(message, ErrorSummary.localError(LocalApiException.CODE_FORBIDDEN));
    }

    /**
     * 自检请求体。
     */
    public static class SelfTestRequest {

        private Long shopId;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }
    }
}
