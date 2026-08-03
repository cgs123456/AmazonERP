package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.*;
import com.amz.result.Result;
import com.amz.service.impl.MultiplatformServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

    // ==================== 平台账号管理 ====================

    @ShopScoped
    @PostMapping("/account")
    public Result<PlatformAccount> createAccount(@RequestBody PlatformAccount account) {
        return Result.success(multiplatformService.createAccount(account));
    }

    @ShopScoped
    @PutMapping("/account/{id}")
    public Result<PlatformAccount> updateAccount(@PathVariable Long id, @RequestBody PlatformAccount account) {
        return Result.success(multiplatformService.updateAccount(id, account));
    }

    @ShopScoped
    @GetMapping("/account/list/{shopId}")
    public Result<List<PlatformAccount>> listAccounts(@PathVariable Long shopId) {
        return Result.success(multiplatformService.listAccounts(shopId));
    }

    @ShopScoped
    @DeleteMapping("/account/{id}")
    public Result<Boolean> deleteAccount(@PathVariable Long id) {
        return Result.success(multiplatformService.deleteAccount(id));
    }

    @PostMapping("/account/{id}/test")
    public Result<Boolean> testConnection(@PathVariable Long id) {
        return Result.success(multiplatformService.testConnection(id));
    }

    // ==================== 商品映射 ====================

    @ShopScoped
    @PostMapping("/product/sync/{shopId}/{platform}")
    public Result<Integer> syncProducts(@PathVariable Long shopId, @PathVariable String platform) {
        return Result.success(multiplatformService.syncProducts(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/product/list/{shopId}")
    public Result<List<PlatformProduct>> listProducts(@PathVariable Long shopId,
                                                       @RequestParam(required = false) String platform) {
        return Result.success(multiplatformService.listProducts(shopId, platform));
    }

    @ShopScoped
    @PostMapping("/product/{productId}/map")
    public Result<Boolean> mapProduct(@PathVariable Long productId,
                                       @RequestParam String amazonAsin,
                                       @RequestParam String amazonSku) {
        return Result.success(multiplatformService.mapProduct(productId, amazonAsin, amazonSku));
    }

    // ==================== 消息管理 ====================

    @ShopScoped
    @PostMapping("/message/sync/{shopId}/{platform}")
    public Result<Integer> syncMessages(@PathVariable Long shopId, @PathVariable String platform) {
        return Result.success(multiplatformService.syncMessages(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/message/list/{shopId}")
    public Result<List<PlatformMessage>> listMessages(@PathVariable Long shopId,
                                                       @RequestParam(required = false) String platform,
                                                       @RequestParam(required = false) Integer status) {
        return Result.success(multiplatformService.listMessages(shopId, platform, status));
    }

    @ShopScoped
    @PostMapping("/message/{messageId}/reply")
    public Result<Boolean> replyMessage(@PathVariable Long messageId,
                                         @RequestParam String replyContent) {
        return Result.success(multiplatformService.replyMessage(messageId, replyContent));
    }

    @ShopScoped
    @PostMapping("/message/{messageId}/assign")
    public Result<Boolean> assignMessage(@PathVariable Long messageId,
                                          @RequestParam String assignedTo) {
        return Result.success(multiplatformService.assignMessage(messageId, assignedTo));
    }

    // ==================== 库存聚合 ====================

    @ShopScoped
    @PostMapping("/inventory/sync/{shopId}/{platform}")
    public Result<Integer> syncInventory(@PathVariable Long shopId, @PathVariable String platform) {
        return Result.success(multiplatformService.syncInventory(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/inventory/list/{shopId}")
    public Result<List<PlatformInventory>> listPlatformInventory(@PathVariable Long shopId,
                                                                  @RequestParam(required = false) String platform) {
        return Result.success(multiplatformService.listPlatformInventory(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/inventory/aggregated/{shopId}")
    public Result<Map<String, Object>> aggregatedInventory(@PathVariable Long shopId) {
        return Result.success(multiplatformService.aggregatedInventory(shopId));
    }

    // ==================== Webhook ====================

    @PostMapping("/webhook/{platform}/{eventType}")
    public Result<WebhookEvent> receiveWebhook(@PathVariable String platform,
                                                @PathVariable String eventType,
                                                @RequestParam(required = false) String eventId,
                                                @RequestBody(required = false) String payload) {
        return Result.success(multiplatformService.receiveWebhook(platform, eventType, eventId, payload));
    }

    @ShopScoped
    @GetMapping("/webhook/list/{shopId}")
    public Result<List<WebhookEvent>> listWebhookEvents(@PathVariable Long shopId,
                                                         @RequestParam(required = false) String status) {
        return Result.success(multiplatformService.listWebhookEvents(shopId, status));
    }

    // ==================== OAuth 开放 API ====================

    @ShopScoped
    @PostMapping("/oauth/app")
    public Result<OauthApp> registerApp(@RequestBody OauthApp app) {
        return Result.success(multiplatformService.registerApp(app));
    }

    @ShopScoped
    @GetMapping("/oauth/app/list/{shopId}")
    public Result<List<OauthApp>> listApps(@PathVariable Long shopId) {
        return Result.success(multiplatformService.listApps(shopId));
    }

    @PostMapping("/oauth/token")
    public Result<OauthToken> generateToken(@RequestParam String appKey,
                                             @RequestParam String appSecret,
                                             @RequestParam(required = false) String[] scopes,
                                             @RequestParam Long shopId) {
        return Result.success(multiplatformService.generateToken(appKey, appSecret, scopes, shopId));
    }

    // ==================== 原有端点 ====================

    @ShopScoped
    @PostMapping("/sync/all/{shopId}")
    public Result<Integer> syncAll(@PathVariable Long shopId) {
        return Result.success(multiplatformService.syncAllPlatforms(shopId));
    }

    @ShopScoped
    @PostMapping("/sync/{shopId}/{platform}")
    public Result<Integer> syncByPlatform(@PathVariable Long shopId, @PathVariable String platform) {
        return Result.success(multiplatformService.syncByPlatform(shopId, platform));
    }

    @ShopScoped
    @GetMapping("/order/list/{shopId}")
    public Result<List<UnifiedOrder>> listOrders(@PathVariable Long shopId) {
        return Result.success(multiplatformService.listOrders(shopId));
    }

    @ShopScoped
    @GetMapping("/order/list/{shopId}/{platform}")
    public Result<List<UnifiedOrder>> listByPlatform(@PathVariable Long shopId, @PathVariable String platform) {
        return Result.success(multiplatformService.listByPlatform(shopId, platform));
    }

    @PostMapping("/order/{orderId}/ship")
    public Result<Boolean> markShipped(@PathVariable Long orderId, @RequestParam String trackingNo) {
        return Result.success(multiplatformService.markShipped(orderId, trackingNo));
    }
}
