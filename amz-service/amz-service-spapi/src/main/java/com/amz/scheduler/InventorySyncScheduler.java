package com.amz.scheduler;

import com.amz.analytics.InventoryHealthAnalyzer;
import com.amz.client.FbaInventoryClient;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.LocalApiException;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.lock.DistributedJobLock;
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
import org.springframework.context.annotation.Profile;
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
@Profile("!bootstrap")

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

    /**
     * P0-51：端点覆盖生效时，库存同步必须跳过——桩数据不得写入业务表。
     */
    @Autowired
    private SpApiEndpointResolver spApiEndpointResolver;

    @Autowired
    private InventoryHealthAnalyzer inventoryHealthAnalyzer;

    @Autowired
    private FbaInventoryMapper fbaInventoryMapper;

    @Autowired
    private InventorySyncLogMapper inventorySyncLogMapper;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /**
     * 每 30 分钟执行一次（上一次执行结束后起算 fixedDelay）。
     * 分布式锁互斥：多实例部署时仅一个实例同步，避免双倍消耗 SP-API 配额与重复写。
     * 租期 29 分钟略小于 30 分钟调度周期。
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void syncInventory() {
        distributedJobLock.runWithLock(
                "amz:sched:inventory-sync",
                29 * 60L,
                this::doSyncInventory);
    }

    private void doSyncInventory() {
        if (skipBecauseEndpointOverride("syncInventory")) {
            return;
        }
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
     * P0-51：端点覆盖生效（非生产桩/联调）期间禁止落库。
     * 定时任务与手动触发共用同一判定，避免把桩数据写进业务表。
     */
    private boolean skipBecauseEndpointOverride(String caller) {
        if (spApiEndpointResolver == null || !spApiEndpointResolver.isOverrideActive()) {
            return false;
        }
        log.warn("{} skipped: {}={} 生效（仅限非生产的桩/联调地址），"
                        + "为避免桩数据污染业务表，本次不调用平台也不落库；恢复真实同步请清空该配置。",
                caller, SpApiEndpointResolver.OVERRIDE_PROPERTY, spApiEndpointResolver.baseUrlOverride());
        return true;
    }

    /**
     * 同步单个店铺的 FBA 库存。供定时任务与手动触发共用。
     *
     * @param shopId 店铺 ID
     * @return 本次同步落库的 SKU 记录数
     */
    public int syncShopInventory(Long shopId) {
        // 手动触发同样受 P0-51 约束（不得只拦定时任务而放过 HTTP 入口）
        if (skipBecauseEndpointOverride("syncShopInventory shopId=" + shopId)) {
            return 0;
        }
        ShopCredential credential = shopCredentialStore.get(shopId);
        if (credential == null || credential.getMarketplaceId() == null) {
            // 缺凭证/缺 marketplace 不能 return 0：HTTP 入口会把 0 包成 Result.success(0)，
            // 与「真同步到 0 条」不可区分，调用方读不出「这家店根本没配凭证」。
            // 对齐 SpapiController.syncOrders 的口径点名失败；定时路径已按店铺 try/catch，
            // 抛出不中断其它店铺同步。
            boolean credentialMissing = credential == null;
            String detail = credentialMissing ? "credential missing" : "marketplaceId missing";
            log.warn("syncShopInventory failed shopId={}: {}", shopId, detail);
            recordLog(shopId, STATUS_FAILED, 0, detail);
            throw LocalApiException.of(
                    credentialMissing ? LocalApiException.CODE_CREDENTIAL_MISSING
                            : LocalApiException.CODE_MARKETPLACE_MISSING,
                    "inventory sync " + detail + " for shopId=" + shopId);
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
    // 包级可见：解析语义（"字段缺失" ≠ "库存为 0"）由同包测试直接覆盖，无需起 HTTP 或连库
    FbaInventory parseInventory(JsonObject item, Long shopId, String marketplaceId) {
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
        inv.setAvailableQuantity(getIntOrNull(details, "fulfillable"));
        inv.setInboundWorking(getIntOrNull(details, "inboundWorking"));
        inv.setInboundShipped(getIntOrNull(details, "inboundShipped"));
        // unfulfillable 在 SP-API 中可能是对象 {totalUnfulfillable: N}，也可能是数字
        inv.setUnfulfillableQuantity(extractUnfulfillable(details));

        inv.setSyncTime(LocalDateTime.now());
        return inv;
    }

    /**
     * 提取 unfulfillable 数量：优先取对象内的 totalUnfulfillable，否则取原始数值。
     * 返回 {@code null} 表示"对端没给这个字段"，与"给了 0"必须可区分。
     */
    private Integer extractUnfulfillable(JsonObject details) {
        if (!details.has("unfulfillable")) {
            return null;
        }
        JsonElement elem = details.get("unfulfillable");
        if (elem == null || elem.isJsonNull()) {
            return null;
        }
        if (elem.isJsonObject()) {
            return getIntOrNull(elem.getAsJsonObject(), "totalUnfulfillable");
        }
        if (elem.isJsonPrimitive()) {
            try {
                return elem.getAsInt();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
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

    /**
     * 读取整数字段，缺失/为 null/不可解析时返回 {@code null}。
     * <p>
     * 旧实现是 {@code getInt(obj, key, 0)}：SP-API 少给一个字段（或结构变了）就等于
     * "可售库存 0"，随后 upsert 把这个假零写进 {@code amz_fba_inventory.available_quantity}，
     * 与真实缺货无法区分，还会驱动补货建议。列本身可空、实体是 Integer，
     * 因此用 null 表达"未知"不需要改表；下游按 null 自行决定保守策略。
     */
    private Integer getIntOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return null;
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
