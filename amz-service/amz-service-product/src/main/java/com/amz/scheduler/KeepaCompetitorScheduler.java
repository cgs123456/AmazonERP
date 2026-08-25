package com.amz.scheduler;

import com.amz.client.KeepaClient;
import com.amz.mapper.CompetitorMonitorMapper;
import com.amz.model.CompetitorMonitor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 竞品价格自动轮询调度器（Keepa）。
 * <p>
 * 此前竞品价格仅能通过手动 POST /product/listing-monitor/competitor 推送，
 * "竞品监控"实际不产生任何自动数据。本调度器每 6 小时：
 * <ol>
 *   <li>查询 amz_competitor_monitor 中出现过的去重 ASIN 集合；</li>
 *   <li>逐个调用 Keepa product 接口（stats=90 携带当前价/评分/BSR）；</li>
 *   <li>为每个 (shopId, asin) 监控关系写入当日快照，并计算与上一快照的价差。</li>
 * </ol>
 * Keepa API key 未配置时 KeepaRealClient 返回 null，本轮静默跳过（不烧积分）。
 */
@Slf4j
@Component
public class KeepaCompetitorScheduler {

    /** Keepa domain id：1=US 2=UK 3=DE 4=FR 5=JP（常用站点映射）。 */
    private static final Map<String, Integer> MARKETPLACE_DOMAIN = Map.of(
            "ATVPDKIKX0DER", 1,
            "A1F83G8C2AR0N7E", 2,
            "A1PA6795UKMFR9", 3,
            "A13V1IB3VIYZZH", 4,
            "A1VC38T7YXB528", 5
    );

    /** Keepa 价格单位为"美分"，换算为元需 ÷100。 */
    private static final BigDecimal KEEPA_PRICE_DIVISOR = BigDecimal.valueOf(100);

    @Autowired
    private CompetitorMonitorMapper competitorMonitorMapper;

    @Autowired
    private KeepaClient keepaClient;

    /**
     * 每 6 小时轮询一次竞品价格。
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000)
    public void pollCompetitorPrices() {
        List<CompetitorMonitor> monitors = competitorMonitorMapper.selectList(
                new LambdaQueryWrapper<CompetitorMonitor>()
                        .select(CompetitorMonitor::getShopId, CompetitorMonitor::getCompetitorAsin,
                                CompetitorMonitor::getMarketplaceId)
                        .groupBy(CompetitorMonitor::getShopId, CompetitorMonitor::getCompetitorAsin,
                                CompetitorMonitor::getMarketplaceId));
        if (monitors.isEmpty()) {
            log.info("Keepa 轮询：无监控中的竞品，跳过");
            return;
        }

        // 同一 ASIN 只调一次 Keepa（多店铺监控同一 ASIN 时共享结果）
        Set<String> fetchedAsins = new HashSet<>();
        Map<String, JsonObject> statsByAsin = new HashMap<>();
        int updated = 0;
        int failed = 0;

        for (CompetitorMonitor m : monitors) {
            String asin = m.getCompetitorAsin();
            String marketplaceId = m.getMarketplaceId() == null ? "ATVPDKIKX0DER" : m.getMarketplaceId();
            if (asin == null || asin.isBlank()) {
                continue;
            }
            JsonObject stats = statsByAsin.computeIfAbsent(asin, a -> {
                if (!fetchedAsins.add(a)) {
                    return null; // 同批次内已请求过且失败，避免重复打 API
                }
                int domain = MARKETPLACE_DOMAIN.getOrDefault(marketplaceId, 1);
                String resp = keepaClient.getCompetitorAnalysis(a, domain);
                return parseKeepaStats(resp);
            });
            if (stats == null) {
                failed++;
                continue;
            }

            try {
                upsertSnapshot(m.getShopId(), asin, marketplaceId, stats);
                updated++;
            } catch (Exception e) {
                log.error("Keepa 快照落库失败 shopId={} asin={}", m.getShopId(), asin, e);
                failed++;
            }
        }
        log.info("Keepa 轮询完成：监控关系 {} 条，快照更新 {}，失败 {}", monitors.size(), updated, failed);
    }

    /**
     * 解析 Keepa product 响应的 stats 字段（stats=90 请求时返回）。
     * 兼容字段缺失：任一指标缺失置 null，由落库层判空。
     */
    private JsonObject parseKeepaStats(String respBody) {
        if (respBody == null || respBody.isEmpty()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
            if (!root.has("products") || !root.get("products").isJsonArray()
                    || root.getAsJsonArray("products").size() == 0) {
                return null;
            }
            JsonObject product = root.getAsJsonArray("products").get(0).getAsJsonObject();
            if (!product.has("stats") || !product.get("stats").isJsonObject()) {
                return null;
            }
            JsonObject stats = product.getAsJsonObject("stats");
            JsonObject out = new JsonObject();
            // current 数组索引：0=Amazon 价 1=New 价 3=SalesRank；18=评分(千分位-1 表示无) 16=评论数
            out.add("currentPrice", pickCurrent(stats, 1));
            out.add("salesRank", pickCurrent(stats, 3));
            out.addProperty("reviewCount", optInt(stats, 16));
            out.addProperty("ratingMilli", optInt(stats, 18));
            out.addProperty("title", product.has("title") && !product.get("title").isJsonNull()
                    ? product.get("title").getAsString() : null);
            return out;
        } catch (Exception e) {
            log.warn("Keepa 响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    private JsonElement pickCurrent(JsonObject stats, int index) {
        if (!stats.has("current") || !stats.get("current").isJsonArray()) {
            return null;
        }
        var arr = stats.getAsJsonArray("current");
        if (index >= arr.size() || arr.get(index).isJsonNull()) {
            return null;
        }
        return arr.get(index);
    }

    private Integer optInt(JsonObject stats, int index) {
        JsonElement el = pickCurrent(stats, index);
        if (el == null) {
            return null;
        }
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写入/更新当日快照：同 (shopId, asin, snapshotDate) 已存在则覆盖，
     * 否则插入；priceChange 相对最近一条历史快照计算。
     */
    private void upsertSnapshot(Long shopId, String asin, String marketplaceId, JsonObject stats) {
        LocalDate today = LocalDate.now();

        CompetitorMonitor latest = competitorMonitorMapper.selectOne(
                new LambdaQueryWrapper<CompetitorMonitor>()
                        .eq(CompetitorMonitor::getShopId, shopId)
                        .eq(CompetitorMonitor::getCompetitorAsin, asin)
                        .orderByDesc(CompetitorMonitor::getSnapshotDate)
                        .last("LIMIT 1"));

        CompetitorMonitor snapshot = new CompetitorMonitor();
        snapshot.setShopId(shopId);
        snapshot.setCompetitorAsin(asin);
        snapshot.setSnapshotDate(today);
        snapshot.setMarketplaceId(marketplaceId);

        JsonElement priceEl = stats.get("currentPrice");
        if (priceEl != null && !priceEl.isJsonNull()) {
            BigDecimal price = priceEl.getAsBigDecimal()
                    .divide(KEEPA_PRICE_DIVISOR, 2, RoundingMode.HALF_UP);
            snapshot.setPrice(price);
            if (latest != null && latest.getPrice() != null && today.equals(latest.getSnapshotDate())) {
                // 当日已有快照 → 视为更新而非新增价差基准
                snapshot.setPriceChange(price.subtract(latest.getPrice()).setScale(2, RoundingMode.HALF_UP));
                snapshot.setId(latest.getId());
            } else if (latest != null && latest.getPrice() != null) {
                snapshot.setPriceChange(price.subtract(latest.getPrice()).setScale(2, RoundingMode.HALF_UP));
            }
        }
        JsonElement rankEl = stats.get("salesRank");
        if (rankEl != null && !rankEl.isJsonNull()) {
            try { snapshot.setBsRank(rankEl.getAsBigDecimal().intValue()); } catch (Exception ignore) { }
        }
        Integer reviewCount = optInt(stats, 16);
        snapshot.setReviewCount(reviewCount);
        Integer ratingMilli = optInt(stats, 18);
        if (ratingMilli != null && ratingMilli > 0) {
            // Keepa 评分为万分位整数（如 4500 = 4.50）
            snapshot.setReviewRating(BigDecimal.valueOf(ratingMilli)
                    .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP));
        }
        String title = stats.has("title") && !stats.get("title").isJsonNull()
                ? stats.get("title").getAsString() : null;
        snapshot.setCompetitorTitle(title != null ? title : (latest != null ? latest.getCompetitorTitle() : null));

        if (snapshot.getId() != null) {
            competitorMonitorMapper.updateById(snapshot);
        } else {
            competitorMonitorMapper.insert(snapshot);
        }
    }
}
