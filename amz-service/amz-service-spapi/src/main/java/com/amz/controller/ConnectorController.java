package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.client.OrdersClient;
import com.amz.connector.ConnectorRegistry;
import com.amz.connector.ErrorSummary;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
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
 * <b>路径（重要偏差）：</b>映射在 {@code /spapi/connectors} 而不是计划原文的
 * {@code /api/connectors}。网关 {@code amz-gateway/application.yml} 只有
 * {@code Path=/spapi/**} 等 15 段 Path= 路由（14 段 lb:// 服务 + 1 段 /ws/**）、没有 {@code /api/**}，挂 {@code /api/connectors}
 * 会造出一个网关永远到不了的死端点。对外统一前缀 {@code /api/connectors} 需要新增网关路由，
 * 属 Plan 2 范围（本 Task 只登记、不实现）。
 * <p>
 * <b>守卫：</b>每个端点都显式带 {@link RequireRole}——{@code ConnectorControllerGuardTest}
 * 用反射断言「新增无守卫端点」会直接失败，避免本类成为附录 F 无守卫清单的新增项。
 * 自检请求体里的 shopId 不能靠 {@code @ShopScoped}（切面只认 {@code @RequestParam}/
 * {@code @PathVariable}），因此显式调用 {@link UserContext#isShopAllowed(Long)}。
 * <p>
 * <b>脱敏：</b>自检结果只回 operation、outcomeCode、耗时与<b>条数</b>；
 * 订单内容含 PII（买家姓名/地址），绝不回传；异常文本一律过 {@link ErrorSummary#redact}。
 */
@RestController
@RequestMapping("/spapi/connectors")
public class ConnectorController {

    private static final Logger log = LoggerFactory.getLogger(ConnectorController.class);

    /** 计划中的对外统一路径（需网关新增 {@code /api/**} 路由后才生效）。 */
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

    @Autowired
    public ConnectorController(ConnectorRegistry registry,
                               ShopCredentialStore credentialStore,
                               OrdersClient ordersClient) {
        this.registry = registry;
        this.credentialStore = credentialStore;
        this.ordersClient = ordersClient;
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
     * 单个连接器能力清单。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/{code}")
    public Result<ConnectorRegistry.Capability> one(@PathVariable String code) {
        ConnectorRegistry.Capability capability = registry.describe(code);
        if (capability == null) {
            return Result.failure("未知连接器：" + code + "（当前只有 " + ConnectorRegistry.SPAPI + "）");
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
            return Result.failure("未知连接器：" + code + "（当前只有 " + ConnectorRegistry.SPAPI + "）");
        }
        if (body == null || body.getShopId() == null) {
            return Result.failure("shopId 不能为空");
        }
        Long shopId = body.getShopId();
        // @ShopScoped 切面不校验 @RequestBody 内嵌 shopId，此处必须显式校验（越权防护）。
        if (!UserContext.isShopAllowed(shopId)) {
            log.warn("连接器自检越权拦截：connector={}, shopId={}, userId={}", code, shopId, UserContext.getUserId());
            return Result.failure("无权操作该店铺 shopId=" + shopId);
        }

        ShopCredential credential = credentialStore.get(shopId);
        if (credential == null) {
            return finish(code, new ConnectorRegistry.SelfTestResult(
                    Instant.now(), false, "orders.getOrders", OUTCOME_CREDENTIAL_MISSING,
                    "no credential for shopId=" + shopId, 0L, 0),
                    "自检未执行：店铺 shopId=" + shopId + " 无凭证（A2 缺凭证必须显式失败，不得回空结果）");
        }
        if (credential.getMarketplaceId() == null || credential.getMarketplaceId().isBlank()) {
            return finish(code, new ConnectorRegistry.SelfTestResult(
                    Instant.now(), false, "orders.getOrders", OUTCOME_MARKETPLACE_MISSING,
                    "marketplaceId missing for shopId=" + shopId, 0L, 0),
                    "自检未执行：店铺 shopId=" + shopId + " 的凭证缺 marketplaceId");
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
            String outcome = outcomeCode(detail);
            log.error("连接器自检失败：connector={}, shopId={}, outcome={}", code, shopId, outcome, e);
            return finish(code, new ConnectorRegistry.SelfTestResult(
                    Instant.now(), false, "orders.getOrders", outcome, detail, elapsedMs, 0),
                    "自检失败：" + outcome + " — " + detail);
        }
    }

    /**
     * 记录自检结果并组装响应（成功与失败都带结构化 data，方便运维直接用）。
     */
    private Result<Map<String, Object>> finish(String code,
                                               ConnectorRegistry.SelfTestResult result,
                                               String message) {
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
        return result.ok() ? Result.success(data) : new Result<>(message, 400, data);
    }

    /**
     * 从脱敏后的异常文本中提取平台状态码；无法识别时返回 {@code UNKNOWN}。
     * <p>
     * 局限（诚实记录）：{@code SpApiGateway} 目前把状态塞进异常消息，只能文本提取；
     * 若将来改为抛出携带状态码的异常类型，应改为读字段而不是正则。
     */
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