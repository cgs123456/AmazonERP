package com.amz.controller;

import com.amz.client.OrdersClient;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

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

    @Autowired
    private OrdersClient ordersClient;

    /**
     * 服务健康检查。
     */
    @GetMapping("/status")
    public Result<String> status() {
        return Result.success("SP-API service running");
    }

    /**
     * 写入或更新店铺凭证（内存存储）。
     */
    @PostMapping("/credential")
    public Result<String> saveCredential(@RequestBody ShopCredential credential) {
        if (credential == null || credential.getShopId() == null) {
            return Result.failure("shopId must not be null");
        }
        shopCredentialStore.put(credential);
        return Result.success("credential stored for shopId=" + credential.getShopId());
    }

    /**
     * 手动触发指定店铺的订单同步（最近 7 天）。
     */
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
            return Result.failure("sync failed: " + e.getMessage());
        }
    }
}
