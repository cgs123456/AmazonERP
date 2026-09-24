package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.client.OrdersClient;
import com.amz.connector.ConnectorSelfDescription;
import com.amz.connector.ErrorSummary;
import com.amz.context.UserContext;
import com.amz.credential.ConnectorStartupCheck;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
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
     * 写入或更新店铺凭证（内存存储）。
     */
    @PostMapping("/credential")
    public Result<String> saveCredential(@RequestBody ShopCredential credential) {
        if (credential == null || credential.getShopId() == null) {
            return Result.failure("shopId must not be null");
        }
        // 越权防护：凭证写入属于高敏感操作（覆盖店铺 AWS AccessKey/SecretKey/LWA refreshToken），
        // 必须校验请求体中的 shopId 属于当前登录用户授权店铺，防止越权篡改其他租户凭证。
        // @ShopScoped 切面仅覆盖 @RequestParam/@PathVariable，无法校验 @RequestBody 内嵌 shopId，故显式校验。
        if (!UserContext.isShopAllowed(credential.getShopId())) {
            log.warn("凭证写入越权拦截：shopId={}, userId={}", credential.getShopId(), UserContext.getUserId());
            return Result.failure("无权写入该店铺凭证");
        }
        shopCredentialStore.put(credential);
        return Result.success("credential stored for shopId=" + credential.getShopId());
    }

    /**
     * 手动触发指定店铺的订单同步（最近 7 天）。
     */
    @ShopScoped
    @PostMapping("/sync/orders")
    public Result<Integer> syncOrders(@RequestParam Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null) {
            return Result.failure("no credential for shopId=" + shopId);
        }
        if (credential.getMarketplaceId() == null) {
            return Result.failure("marketplaceId missing for shopId=" + shopId);
        }

        Instant createdAfter = Instant.now().minusSeconds(MANUAL_SYNC_WINDOW_SECONDS);
        try {
            List<JsonObject> orders = ordersClient.fetchOrders(
                    shopId, credential.getMarketplaceId(), createdAfter, DEFAULT_ORDER_STATUSES);
            log.info("manual sync orders shopId={} count={}", shopId, orders.size());
            return Result.success(orders.size());
        } catch (Exception e) {
            log.error("manual sync orders failed shopId={}", shopId, e);
            return Result.failure("sync failed: " + ErrorSummary.of(e));
        }
    }

    /**
     * 查询指定店铺的 FBA 库存列表（供 Agent 工具调用）。
     */
    @ShopScoped
    @GetMapping("/inventory/{shopId}")
    public Result<List<FbaInventory>> getInventory(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        List<FbaInventory> list = fbaInventoryMapper.selectList(
                new LambdaQueryWrapper<FbaInventory>()
                        .eq(FbaInventory::getShopId, shopId));
        return Result.success(list);
    }

    /**
     * 查询库存健康度列表（供 Agent 工具调用）。
     */
    @ShopScoped
    @GetMapping("/inventory/health")
    public Result<List<FbaInventory>> getInventoryHealth(@RequestParam Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
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
    @ShopScoped
    @GetMapping("/replenish/suggest")
    public Result<List<ReplenishmentSuggestion>> getReplenishSuggest(
            @RequestParam Long shopId,
            @RequestParam(required = false) String sku) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
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
