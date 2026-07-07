package com.amz.scheduler;

import com.amz.analytics.InventoryHealthAnalyzer;
import com.amz.client.FbaInventoryClient;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.mapper.FbaInventoryMapper;
import com.amz.mapper.InventorySyncLogMapper;
import com.amz.model.FbaInventory;
import com.amz.model.InventorySyncLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * FBA 库存定时同步调度器。
 * <p>
 * 每 30 分钟轮询一次活跃店铺，拉取 FBA 库存汇总、计算健康度、upsert 入库。
 * 单店失败不影响其他店铺同步，每次同步均记录 {@link InventorySyncLog}。
 */
@Component
public class InventorySyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncScheduler.class);

    /**
     * 同步日志记录的 syncType。
     */
    private static final String SYNC_TYPE_INVENTORY = "INVENTORY";

    /**
     * 同步日志成功状态。
     */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * 同步日志失败状态。
     */
    private static final String STATUS_FAILED = "FAILED";

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    @Autowired
    private FbaInventoryClient fbaInventoryClient;

    @Autowired
    private InventoryHealthAnalyzer inventoryHealthAnalyzer;

    @Autowired
    private FbaInventoryMapper fbaInventoryMapper;

    @Autowired
    private InventorySyncLogMapper inventorySyncLogMapper;

    /**
     * 每 30 分钟执行一次（上一次执行结束后起算 fixedDelay）。
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void syncInventory() {
        Set<Long> shopIds = shopCredentialStore.getActiveShopIds();
        if (shopIds.isEmpty()) {
            log.info("syncInventory: no active shops, skipping");
            return;
        }
        log.info("syncInventory start: shopCount={}", shopIds.size());
        for (Long shopId : shopIds) {
            // 单店异常不影响其他店铺
            try {
                syncShopInventory(shopId);
            } catch (Exception e) {
                log.error("syncInventory failed shopId={}", shopId, e);
            }
        }
        log.info("syncInventory done");
    }

    /**
     * 同步单个店铺的 FBA 库存。供定时任务与手动触发共用。
     *
     * @param shopId 店铺 ID
     * @return 本次同步落库的 SKU 记录数
     */
    public int syncShopInventory(Long shopId) {
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null || credential.getMarketplaceId() == null) {
            log.warn("syncShopInventory skip shopId={}: credential or marketplaceId missing", shopId);
            recordLog(shopId, STATUS_FAILED, 0, "credential or marketplaceId missing");
            return 0;
        }
        String marketplaceId = credential.getMarketplaceId();
        LocalDateTime startTime = LocalDateTime.now();
        try {
            List<JsonObject> items = fbaInventoryClient.fetchAllInventory(shopId, marketplaceId);
            int synced = 0;
            for (JsonObject item : items) {
                FbaInventory inv = parseInventory(item, shopId, marketplaceId);
                if (inv == null || inv.getSku() == null) {
                    continue;
                }
                inventoryHealthAnalyzer.analyze(inv);
                upsert(inv);
                synced++;
            }
            recordLog(shopId, STATUS_SUCCESS, synced, null);
            log.info("syncShopInventory shopId={} synced={} skus", shopId, synced);
            return synced;
        } catch (Exception e) {
            log.error("syncShopInventory failed shopId={}", shopId, e);
            recordLog(shopId, STATUS_FAILED, 0, e.getMessage());
            throw new RuntimeException(e);
        } finally {
            log.debug("syncShopInventory shopId={} elapsed={}ms", shopId,
                    java.time.Duration.between(startTime, LocalDateTime.now()).toMillis());
        }
    }

    /**
     * 将 SP-API 返回的 inventorySummaries 元素解析为 FbaInventory 实体。
     * <p>
     * 解析结构：{marketplaceId, asin, fnSku, sellerSku, productName, lastUpdatedTime,
     * inventoryDetails.{fulfillable, unfulfillable, inboundWorking, inboundShipped}}
     */
    private FbaInventory parseInventory(JsonObject item, Long shopId, String marketplaceId) {
        FbaInventory inv = new FbaInventory();
        inv.setShopId(shopId);
        // marketplaceId 优先取响应内字段，缺失时回退到查询参数
        inv.setMarketplaceId(getString(item, "marketplaceId", marketplaceId));
        inv.setAsin(getString(item, "asin", null));
        inv.setFnSku(getString(item, "fnSku", null));
        inv.setSku(getString(item, "sellerSku", null));
        inv.setProductName(getString(item, "productName", null));
        inv.setLastUpdatedTime(parseInstant(getString(item, "lastUpdatedTime", null)));

        JsonObject details = item.has("inventoryDetails") && item.get("inventoryDetails").isJsonObject()
                ? item.getAsJsonObject("inventoryDetails") : new JsonObject();
        inv.setAvailableQuantity(getInt(details, "fulfillable", 0));
        inv.setInboundWorking(getInt(details, "inboundWorking", 0));
        inv.setInboundShipped(getInt(details, "inboundShipped", 0));
        // unfulfillable 在 SP-API 中可能是对象 {totalUnfulfillable: N}，也可能是数字
        inv.setUnfulfillableQuantity(extractUnfulfillable(details));

        inv.setSyncTime(LocalDateTime.now());
        return inv;
    }

    /**
     * 提取 unfulfillable 数量：优先取对象内的 totalUnfulfillable，否则取原始数值。
     */
    private int extractUnfulfillable(JsonObject details) {
        if (!details.has("unfulfillable")) {
            return 0;
        }
        JsonElement elem = details.get("unfulfillable");
        if (elem == null || elem.isJsonNull()) {
            return 0;
        }
        if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            return getInt(obj, "totalUnfulfillable", 0);
        }
        if (elem.isJsonPrimitive()) {
            try {
                return elem.getAsInt();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * upsert：按 (shopId, marketplaceId, sku) 唯一键查询，存在则 updateById，否则 insert。
     */
    private void upsert(FbaInventory inv) {
        FbaInventory existing = fbaInventoryMapper.selectOne(
                new LambdaQueryWrapper<FbaInventory>()
                        .eq(FbaInventory::getShopId, inv.getShopId())
                        .eq(FbaInventory::getMarketplaceId, inv.getMarketplaceId())
                        .eq(FbaInventory::getSku, inv.getSku()));
        if (existing != null) {
            inv.setId(existing.getId());
            fbaInventoryMapper.updateById(inv);
        } else {
            fbaInventoryMapper.insert(inv);
        }
    }

    /**
     * 记录同步日志。
     */
    private void recordLog(Long shopId, String status, int recordsSynced, String errorMessage) {
        try {
            InventorySyncLog logEntry = new InventorySyncLog();
            logEntry.setShopId(shopId);
            logEntry.setSyncType(SYNC_TYPE_INVENTORY);
            logEntry.setStatus(status);
            logEntry.setRecordsSynced(recordsSynced);
            // 错误信息截断，避免过长
            if (errorMessage != null && errorMessage.length() > 1000) {
                logEntry.setErrorMessage(errorMessage.substring(0, 1000));
            } else {
                logEntry.setErrorMessage(errorMessage);
            }
            LocalDateTime now = LocalDateTime.now();
            logEntry.setStartTime(now);
            logEntry.setEndTime(now);
            inventorySyncLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("recordLog failed shopId={}", shopId, e);
        }
    }

    // ==================== JsonObject 安全取值工具 ====================

    private String getString(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析 ISO-8601 瞬时字符串为本地日期时间（UTC）。
     */
    private LocalDateTime parseInstant(String iso) {
        if (iso == null || iso.isEmpty()) {
            return null;
        }
        try {
            Instant instant = Instant.parse(iso);
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("parseInstant failed for value={}", iso);
            return null;
        }
    }
}
