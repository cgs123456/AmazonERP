package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.OauthApp;
import com.amz.model.OauthToken;
import com.amz.model.OauthTokenRequest;
import com.amz.model.PlatformAccount;
import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import com.amz.dto.PlatformOrderStats;
import com.amz.service.impl.PlatformOrderStatsService;
import com.amz.model.UnifiedOrder;
import com.amz.model.WebhookEvent;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.impl.MultiplatformServiceImpl;
import com.amz.service.impl.WebhookEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多平台管理 REST 端点（Phase 3 升级版）。
 * <p>
 * 新增：平台账号管理、商品映射、消息管理、库存聚合、Webhook、OAuth。
 */
@RestController
@RequestMapping("/multiplatform")
public class MultiplatformController {

    @Autowired
    private MultiplatformServiceImpl multiplatformService;

    /**
     * 平台回调域（§S33.90 从 {@link MultiplatformServiceImpl} 拆出）。
     * <p>
     * 直接注入而不经 multiplatformService 转发：webhook 是唯一不带用户 JWT 的入口，
     * 验签与归属判定都收在 {@link WebhookEventService} 里，让它单独可见比藏在
     * 二十多个方法的大服务后面更好查。接口上不留转发方法——那是兼容层，
     * 内部重构按 CLAUDE.md「删除优于兼容」直接改调用方。
     */
    @Autowired
    private WebhookEventService webhookEventService;

    @Autowired
    private PlatformOrderStatsService platformOrderStatsService;

    // ==================== 平台账号管理 ====================

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "绑定账号", description = "绑定第三方平台账号")
    @ShopScoped
    @PostMapping("/account")
    public Result<PlatformAccount> createAccount(@RequestBody PlatformAccount account) {
        return Result.success(multiplatformService.createAccount(account));
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "改账号", description = "修改第三方平台账号")
    @ShopScoped
    @PutMapping("/account/{id}")
    public Result<PlatformAccount> updateAccount(@PathVariable Long id, @RequestBody PlatformAccount account) {
        return Result.success(multiplatformService.updateAccount(id, account));
    }

    @ShopScoped
    @GetMapping("/account/list/{shopId}")
    public Result<List<PlatformAccount>> listAccounts(@PathVariable Long shopId,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String cursor) {
        return Result.paged(multiplatformService.listAccounts(shopId, PageRequest.of(size, cursor)));
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "解绑账号", description = "解绑第三方平台账号")
    @ShopScoped
    @DeleteMapping("/account/{id}")
    public Result<Boolean> deleteAccount(@PathVariable Long id) {
        return Result.success(multiplatformService.deleteAccount(id));
    }

    // 无 shopId 参数（id 是平台账号主键），切面解析不到 → 不能挂 @ShopScoped；
    // 归属由 service 层 requireShopOnRow 按行判定。加锁只为了挡住 VIEWER
    // 拿别人账号反复探测外部平台（会刷对方 API 配额）。
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "测连接", description = "探测第三方平台账号连通性")
    @PostMapping("/account/{id}/test")
    public Result<Boolean> testConnection(@PathVariable Long id) {
        // 亚马逊这类本模块不探测的平台会点名拒绝，要走成前端读得懂的业务失败，
        // 不能被全局兜底成「服务器内部错误」——那会把"没有这个能力"说成"服务坏了"。
        return guarded(() -> multiplatformService.testConnection(id));
    }


    /**
     * 平台同步的"能力未接入/平台不支持"要变成前端读得懂的业务失败，
     * 而不是被全局兜底成 500「服务器内部错误」。真实调用失败（网络/对端）不在此列，
     * 仍按异常上抛，避免把故障伪装成"功能没开"。
     */
    private <T> Result<T> guarded(java.util.function.Supplier<T> action) {
        try {
            return Result.success(action.get());
        } catch (UnsupportedOperationException | com.amz.exception.AttrIsNullException e) {
            return Result.failure(e.getMessage());
        }
    }
    // ==================== 商品映射 ====================

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "同步商品", description = "从第三方平台同步商品")
    @ShopScoped
    @PostMapping("/product/sync/{shopId}/{platform}")
    public Result<Integer> syncProducts(@PathVariable Long shopId, @PathVariable String platform) {
        return guarded(() -> multiplatformService.syncProducts(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/product/list/{shopId}")
    public Result<List<PlatformProduct>> listProducts(@PathVariable Long shopId,
                                                       @RequestParam(required = false) String platform,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String cursor) {
        return Result.paged(multiplatformService.listProducts(
                shopId, platform, PageRequest.of(size, cursor)));
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "映射商品", description = "把平台商品映射到亚马逊 ASIN/SKU")
    @ShopScoped
    @PostMapping("/product/{productId}/map")
    public Result<Boolean> mapProduct(@PathVariable Long productId,
                                       @RequestParam String amazonAsin,
                                       @RequestParam String amazonSku) {
        return Result.success(multiplatformService.mapProduct(productId, amazonAsin, amazonSku));
    }

    // ==================== 消息管理 ====================

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "同步消息", description = "从第三方平台同步买家消息")
    @ShopScoped
    @PostMapping("/message/sync/{shopId}/{platform}")
    public Result<Integer> syncMessages(@PathVariable Long shopId, @PathVariable String platform) {
        return guarded(() -> multiplatformService.syncMessages(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/message/list/{shopId}")
    public Result<List<PlatformMessage>> listMessages(@PathVariable Long shopId,
                                                       @RequestParam(required = false) String platform,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String cursor) {
        return Result.paged(multiplatformService.listMessages(
                shopId, platform, status, PageRequest.of(size, cursor)));
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "回复消息", description = "以店铺身份回复第三方平台买家消息")
    @ShopScoped
    @PostMapping("/message/{messageId}/reply")
    public Result<Boolean> replyMessage(@PathVariable Long messageId,
                                         @RequestParam String replyContent) {
        // 「这家平台的发送接口没接入」必须是点名的业务失败，而不是全局兜底的 500；
        // 真实调用失败（网络/对端拒绝）仍按异常上抛，不伪装成功也不伪装成"功能没开"。
        return guarded(() -> multiplatformService.replyMessage(messageId, replyContent));
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "指消息", description = "指派平台消息处理人")
    @ShopScoped
    @PostMapping("/message/{messageId}/assign")
    public Result<Boolean> assignMessage(@PathVariable Long messageId,
                                          @RequestParam String assignedTo) {
        return Result.success(multiplatformService.assignMessage(messageId, assignedTo));
    }

    // ==================== 库存聚合 ====================

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "同步库存", description = "从第三方平台同步库存")
    @ShopScoped
    @PostMapping("/inventory/sync/{shopId}/{platform}")
    public Result<Integer> syncInventory(@PathVariable Long shopId, @PathVariable String platform) {
        return guarded(() -> multiplatformService.syncInventory(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/inventory/list/{shopId}")
    public Result<List<PlatformInventory>> listPlatformInventory(@PathVariable Long shopId,
                                                                  @RequestParam(required = false) String platform,
                                                                  @RequestParam(required = false) Integer size,
                                                                  @RequestParam(required = false) String cursor) {
        return Result.paged(multiplatformService.listPlatformInventory(
                shopId, platform, PageRequest.of(size, cursor)));
    }

    @ShopScoped
    @GetMapping("/inventory/aggregated/{shopId}")
    public Result<Map<String, Object>> aggregatedInventory(@PathVariable Long shopId) {
        return Result.success(multiplatformService.aggregatedInventory(shopId));
    }

    // ==================== Webhook ====================

    /**
     * 平台回调统一入口（免 JWT 白名单，鉴权靠端点自身的 HMAC 验签）。
     * <p>
     * 末尾那个 {@code null} 是<b>刻意</b>传给 service 的 shopId，不是漏填。
     * §S33.88：不要顺手加一个 {@code @RequestParam Long shopId} 把它接上——
     * 验签只对 {@code payload} 做 HMAC，shopId 若走查询参数就是<b>未签名字段</b>，
     * 任何能构造合法签名的人都能把回调伪造成别的店铺，跨租户写入。
     * 归属由 service 的 {@code resolveShopIdByPlatform} 反查：唯一 ACTIVE 店铺时命中，
     * 无法唯一确定时拒绝落库。要真正支持同平台多店铺的回调，需要平台侧提供
     * 「带店铺标识且被签名覆盖」的字段——属接入方案变更，不是这里能补的。
     */
    @PostMapping("/webhook/{platform}/{eventType}")
    public Result<WebhookEvent> receiveWebhook(@PathVariable String platform,
                                                @PathVariable String eventType,
                                                @RequestParam(required = false) String eventId,
                                                @RequestBody(required = false) String payload,
                                                @RequestHeader(value = "X-Signature", required = false) String signature) {
        return Result.success(webhookEventService.receiveWebhook(platform, eventType, eventId, payload, null, signature));
    }

    @ShopScoped
    @GetMapping("/webhook/list/{shopId}")
    public Result<List<WebhookEvent>> listWebhookEvents(@PathVariable Long shopId,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) Integer size,
                                                         @RequestParam(required = false) String cursor) {
        return Result.paged(webhookEventService.listWebhookEvents(
                shopId, status, PageRequest.of(size, cursor)));
    }

    // ==================== OAuth 应用管理（§S33.94：仅应用 CRUD 可用，token 不构成访问凭证） ====================
    //
    // 这一区只有前三件事是真的可用的：注册应用、轮换密钥、查应用列表。
    // 它们操作 amz_oauth_app，与「能不能凭 token 访问系统」无关。
    //
    // 下面的 /oauth/token 是<b>未完成的半成品</b>：它会校验 appKey/appSecret、
    // 按归属店铺绑 shopId、做 scope 交集，然后签一串 oat_/ort_ 存进 amz_oauth_token。
    // 但全仓没有任何代码读那张表——没有 token 校验、没有过滤器、没有过期/吊销/刷新。
    // 也就是说这串 token 打不开任何受保护资源。
    //
    // 决策（已确认外部无使用方）：保留端点不动，因为删接口是破坏性变更、且它本身无害
    // （拿到一个用不上的字符串）；但所有注释与文档必须写明「签发即终点」，
    // 不再让它冒充已具备的能力。补全需要一整套鉴权体系，属排期项而非缺陷修补。

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "注册应用", description = "注册第三方平台 OAuth 应用")
    @ShopScoped
    @PostMapping("/oauth/app")
    public Result<Map<String, Object>> registerApp(@RequestBody OauthApp app) {
        return Result.success(multiplatformService.registerApp(app));
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "轮换密钥", description = "轮换第三方应用密钥")
    @ShopScoped
    @PostMapping("/oauth/app/{id}/rotate")
    public Result<Map<String, Object>> rotateAppSecret(@PathVariable Long id) {
        return Result.success(multiplatformService.rotateAppSecret(id));
    }

    @ShopScoped
    @GetMapping("/oauth/app/list/{shopId}")
    public Result<List<OauthApp>> listApps(@PathVariable Long shopId,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String cursor) {
        return Result.paged(multiplatformService.listApps(shopId, PageRequest.of(size, cursor)));
    }

    /**
     * 用 appKey + appSecret 换 token。<b>半成品：签发即终点。</b>
     *
     * <p>能正常工作的部分：必填校验、App 存在且 ACTIVE、密钥比对、绑定店铺、
     * scope 取交集（交集为空拒绝）。这些都没问题。
     *
     * <p>不工作的部分：返回的 {@code accessToken} / {@code refreshToken}
     * 只是两个随机串，写入 `amz_oauth_token` 后<b>没有任何代码再读它</b>。
     * 没有校验端点、没有鉴权过滤器、没有过期判定、没有吊销、没有刷新。
     * 调用方拿到它会发现：拿它去请求任何受保护资源，得到的响应与不带 token 完全一样。
     *
     * <p><b>为什么这里没有 shopId 归属校验却仍然安全</b>：因为 token 不授予任何访问权，
     * 「伪造归属」换来的也是一个用不上的字符串。真要补全鉴权时，
     * 这里的归属校验必须和受保护资源一起做——单独存在时它防的不是越权，是空转。
     *
     * <p>保留而非删除：删端点属破坏性变更，而它当前无害（见分区注释的决策记录）。
     */
    @PostMapping("/oauth/token")
    public Result<OauthToken> generateToken(@RequestBody OauthTokenRequest request) {
        return Result.success(multiplatformService.generateToken(
                request.getAppKey(), request.getAppSecret(), request.getScopes(), request.getShopId()));
    }

    // ==================== 原有端点 ====================

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "全平台同步", description = "触发全平台订单同步")
    @ShopScoped
    @PostMapping("/sync/all/{shopId}")
    public Result<MultiplatformServiceImpl.OrderSyncSummary> syncAll(@PathVariable Long shopId) {
        return Result.success(multiplatformService.syncAllPlatforms(shopId));
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "同步订单", description = "触发单平台订单同步")
    @ShopScoped
    @PostMapping("/sync/{shopId}/{platform}")
    public Result<Integer> syncByPlatform(@PathVariable Long shopId, @PathVariable String platform) {
        return Result.success(multiplatformService.syncByPlatform(shopId, platform));
    }

    /**
     * 多平台订单的分平台统计（A8）。
     * <p>
     * 口径：每个平台各给一份（订单量 / 按币种分组的金额 / 状态分布），
     * <b>不产跨平台的合计</b>——各平台币种与费用结构不同，直接相加的
     * 「全店销售额」无法对应任何真实经营口径，页面也不渲染总计。
     * <p>
     * 金额按币种分组而非合并：同平台多币种时混加同样是假数字。
     * 触顶时 truncatedPlatforms 点名哪些平台没算全——静默截断会让
     * 「5000 单的统计」被当成「全部订单的统计」。
     */
    @ShopScoped
    @GetMapping("/order/stats/{shopId}")
    public Result<PlatformOrderStats> orderStats(
            @PathVariable Long shopId,
            @RequestParam(required = false) List<String> platform) {
        return Result.success(platformOrderStatsService.stats(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/order/list/{shopId}")
    public Result<List<UnifiedOrder>> listOrders(@PathVariable Long shopId,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String cursor) {
        return Result.paged(multiplatformService.listOrders(shopId, PageRequest.of(size, cursor)));
    }

    @ShopScoped
    @GetMapping("/order/list/{shopId}/{platform}")
    public Result<List<UnifiedOrder>> listByPlatform(@PathVariable Long shopId, @PathVariable String platform,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String cursor) {
        return Result.paged(multiplatformService.listByPlatform(shopId, platform, PageRequest.of(size, cursor)));
    }

    // 无 shopId 参数 → 不能挂 @ShopScoped；归属由 service 层按行判定。
    // 这是本模块最危险的端点：VIEWER 此前可把任意平台订单标记为已发货。
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "多平台", action = "标记发货", description = "把第三方平台订单标记为已发货")
    @PostMapping("/order/{orderId}/ship")
    public Result<Boolean> markShipped(@PathVariable Long orderId, @RequestParam String trackingNo) {
        return Result.success(multiplatformService.markShipped(orderId, trackingNo));
    }
}
