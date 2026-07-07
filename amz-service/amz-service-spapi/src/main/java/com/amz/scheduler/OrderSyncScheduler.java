package com.amz.scheduler;

import com.amz.client.OrdersClient;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 订单定时同步调度器。
 * <p>
 * 每 15 分钟轮询一次活跃店铺，拉取最近 7 天订单并记录数量。
 * 单店失败不影响其他店铺同步。
 */
@Component
public class OrderSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderSyncScheduler.class);

    /**
     * 同步时间窗口：最近 7 天。
     */
    private static final long SYNC_WINDOW_SECONDS = 7L * 24 * 3600L;

    /**
     * 默认拉取的订单状态。
     */
    private static final List<String> DEFAULT_ORDER_STATUSES =
            List.of("Shipped", "PartiallyShipped", "Unshipped");

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    @Autowired
    private OrdersClient ordersClient;

    /**
     * 每 15 分钟执行一次（上一次执行结束后起算 fixedDelay）。
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void syncOrders() {
        Set<Long> shopIds = shopCredentialStore.getActiveShopIds();
        if (shopIds.isEmpty()) {
            log.info("syncOrders: no active shops, skipping");
            return;
        }

        Instant createdAfter = Instant.now().minusSeconds(SYNC_WINDOW_SECONDS);
        log.info("syncOrders start: shopCount={} createdAfter={}", shopIds.size(), createdAfter);

        for (Long shopId : shopIds) {
            ShopCredential credential = shopCredentialStore.get(shopId);
            if (credential == null || credential.getMarketplaceId() == null) {
                log.warn("syncOrders skip shopId={}: credential or marketplaceId missing", shopId);
                continue;
            }
            try {
                List<JsonObject> orders = ordersClient.fetchOrders(
                        shopId, credential.getMarketplaceId(), createdAfter, DEFAULT_ORDER_STATUSES);
                log.info("syncOrders shopId={} fetched={} orders", shopId, orders.size());
            } catch (Exception e) {
                log.error("syncOrders failed shopId={}", shopId, e);
            }
        }
        log.info("syncOrders done");
    }
}
