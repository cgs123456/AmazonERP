package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.OrdersClient;
import com.amz.connector.ConnectorSelfDescription;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.context.UserContext;
import com.amz.credential.ConnectorStartupCheck;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialAdminService;
import com.amz.credential.ShopCredentialConcurrentUpdateException;
import com.amz.credential.ShopCredentialStore;
import com.amz.credential.ShopCredentialUpdateRequest;
import com.amz.credential.ShopCredentialValidator;
import com.amz.mapper.FbaInventoryMapper;
import com.amz.mapper.ReplenishmentSuggestionMapper;
import com.amz.model.FbaInventory;
import com.amz.model.ReplenishmentSuggestion;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SP-API 服务对外接口。
 */
@RestController
@RequestMapping("/spapi")
public class SpapiController {

    private static final Logger log = LoggerFactory.getLogger(SpapiController.class);

    /**
     * 手动同步时默认拉取最近 7 天订单。
     */
    private static final long MANUAL_SYNC_WINDOW_SECONDS = 7L * 24 * 3600L;

    private static final List<String> DEFAULT_ORDER_STATUSES =
            List.of("Shipped", "PartiallyShipped", "Unshipped");

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    /** 旧凭证入口保持兼容，但写路径必须收口到 CAS 管理服务。 */
    @Autowired
    private ShopCredentialAdminService shopCredentialAdminService;

    /**
     * 启动自检快照来源（P0-52d）：用于让 {@code GET /spapi/status} 回报
     * <b>启动时</b>真实生效的 profile / mock 开关 / 已加载凭证条数。
     */
    @Autowired
    private ConnectorStartupCheck connectorStartupCheck;

    /**
     * 实时 Spring 环境（启动自检未执行时的回落来源）。
     */
    @Autowired
    private Environment environment;

    @Autowired
    private OrdersClient ordersClient;

    @Autowired
    private FbaInventoryMapper fbaInventoryMapper;

    @Autowired
    private ReplenishmentSuggestionMapper replenishmentSuggestionMapper;

    /**
     * 服务健康检查 + 连接器自描述（P0-52d）。
     * <p>
     * 返回固定键集合的只读视图：{@code service} / {@code connector} / {@code profile} /
     * {@code mockClientsActive} / {@code startupCheckRan} / {@code startupRequireCredentials} /
     * {@code loadedCredentialCount}。字段含义与装配见
     * {@link ConnectorSelfDescription}。
     * <p>
     * <b>为什么不只是健康检查：</b>凭证到位当天的验收记录有硬约束「被测服务必须以
     * {@code SPRING_PROFILES_ACTIVE=prod} 启动」——mock profile 下财务域三类客户端返回
     * 离线样例数据，据此产出的「成功样例」是假证据。固定串响应让这条约束在进程外无法核验，
     * 只能靠人工声明；现在验收 runner（{@code tools/connector-acceptance/}）可以直接读到
     * 生效 profile 与 mock 开关并据此<b>拒绝</b>产出记录。
     * <p>
     * <b>不返回任何机密</b>：只回报 profile 名、布尔开关与凭证<b>条数</b>
     * （clientId / clientSecret / refreshToken / accessKey / secretKey / access_token 一律不出现）。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(ConnectorSelfDescription.of(
                environment,
                connectorStartupCheck == null ? null : connectorStartupCheck.getLastState()));
    }

    /**
     * 写入或更新店铺凭证（兼容旧入口；加密后持久化，并更新内存缓存）。
     *
     * @deprecated 请使用 {@code PUT /spapi/credentials/shop/{shopId}}。旧入口仅为兼容历史调用保留，
     * 现在也只允许 ADMIN，并委托带乐观锁的管理服务执行 CAS 写入，不能再无条件覆盖并发更新。
     */
    @Deprecated
    @RequireRole({"ADMIN"})
    @PostMapping("/credential")
    public Result<String> saveCredential(@RequestBody ShopCredential credential) {
        if (credential == null || credential.getShopId() == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        // @ShopScoped 切面只覆盖 @RequestParam / @PathVariable，无法校验 @RequestBody 内嵌 shopId，故显式校验。
        if (!UserContext.isShopAllowedStrict(credential.getShopId())) {
            log.warn("凭证写入越权拦截：shopId={}, userId={}", credential.getShopId(), UserContext.getUserId());
            return Result.failure("无权写入该店铺凭证",
                    ErrorSummary.localError(LocalApiException.CODE_FORBIDDEN));
        }
        List<String> problems = ShopCredentialValidator.validate(credential);
        if (!problems.isEmpty()) {
            // 只回字段名，绝不回显任何字段值。
            return Result.failure("invalid credential fields: " + String.join(",", problems),
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        try {
            shopCredentialAdminService.upsert(credential.getShopId(), toUpdateRequest(credential));
            return Result.success("credential stored for shopId=" + credential.getShopId());
        } catch (ShopCredentialConcurrentUpdateException e) {
            return Result.failure(e.getMessage(),
                    ErrorSummary.localError(LocalApiException.CODE_CONFLICT));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage(),
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
    }

    private static ShopCredentialUpdateRequest toUpdateRequest(ShopCredential credential) {
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setClientId(credential.getClientId());
        request.setClientSecret(credential.getClientSecret());
        request.setRefreshToken(credential.getRefreshToken());
        request.setAccessKey(credential.getAccessKey());
        request.setSecretKey(credential.getSecretKey());
        request.setRegion(credential.getRegion());
        request.setMarketplaceId(credential.getMarketplaceId());
        request.setSellerId(credential.getSellerId());
        return request;
    }

    /**
     * 手动触发指定店铺的订单同步（最近 7 天）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/sync/orders")
    public Result<Integer> syncOrders(@RequestParam Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            return Result.failure("no credential for shopId=" + shopId,
                    ErrorSummary.localError(LocalApiException.CODE_CREDENTIAL_MISSING));
        }
        if (credential.getMarketplaceId() == null) {
            return Result.failure("marketplaceId missing for shopId=" + shopId,
                    ErrorSummary.localError(LocalApiException.CODE_MARKETPLACE_MISSING));
        }

        Instant createdAfter = Instant.now().minusSeconds(MANUAL_SYNC_WINDOW_SECONDS);
        try {
            List<JsonObject> orders = ordersClient.fetchOrders(
                    shopId, credential.getMarketplaceId(), createdAfter, DEFAULT_ORDER_STATUSES);
            log.info("manual sync orders shopId={} count={}", shopId, orders.size());
            return Result.success(orders.size());
        } catch (Exception e) {
            log.error("manual sync orders failed shopId={}", shopId, e);
            return Result.failure("sync failed: " + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }

    /**
     * 查询指定店铺的 FBA 库存列表（供 Agent 工具调用）。
     */
    @InternalServiceAccess("amz-service-ai")
    @ShopScoped
    @GetMapping("/inventory/{shopId}")
    public Result<List<FbaInventory>> getInventory(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        List<FbaInventory> list = fbaInventoryMapper.selectList(
                new LambdaQueryWrapper<FbaInventory>()
                        .eq(FbaInventory::getShopId, shopId));
        return Result.success(list);
    }

    /**
     * 查询库存健康度列表（供 Agent 工具调用）。
     */
    @InternalServiceAccess("amz-service-ai")
    @ShopScoped
    @GetMapping("/inventory/health")
    public Result<List<FbaInventory>> getInventoryHealth(@RequestParam Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        List<FbaInventory> list = fbaInventoryMapper.selectList(
                new LambdaQueryWrapper<FbaInventory>()
                        .eq(FbaInventory::getShopId, shopId)
                        .orderByAsc(FbaInventory::getHealthStatus));
        return Result.success(list);
    }

    /**
     * 查询补货建议（供 Agent 工具调用）。
     */
    @InternalServiceAccess("amz-service-ai")
    @ShopScoped
    @GetMapping("/replenish/suggest")
    public Result<List<ReplenishmentSuggestion>> getReplenishSuggest(
            @RequestParam Long shopId,
            @RequestParam(required = false) String sku) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        LambdaQueryWrapper<ReplenishmentSuggestion> qw = new LambdaQueryWrapper<ReplenishmentSuggestion>()
                .eq(ReplenishmentSuggestion::getShopId, shopId)
                .orderByDesc(ReplenishmentSuggestion::getUrgencyLevel);
        if (sku != null && !sku.trim().isEmpty()) {
            qw.eq(ReplenishmentSuggestion::getSku, sku);
        }
        return Result.success(replenishmentSuggestionMapper.selectList(qw));
    }
}
