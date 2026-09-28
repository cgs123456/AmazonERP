package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.credential.AdvertisingCredentialProvider;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.mapper.AdDailyReportMapper;
import com.amz.model.AdCampaign;
import com.amz.model.AdCampaignExt;
import com.amz.model.AdDailyReport;
import com.amz.model.AdReport;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 广告日报同步调度器：定时拉取 Advertising API Reports，按天落库到
 * {@code amz_ad_daily_report}（趋势图 {@code GET /ad/trend} 的数据源）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>每天 02:00 执行（cron: 0 0 2 * * *），错开 spapi 06:00 补货高峰；
 *       分布式锁防多实例双跑（同 BidScheduleExecutor 套路）；</li>
 *   <li>店铺来源为 {@code amz_ad_campaign_ext} 去重 shop_id，自包含、无跨服务依赖；
 *       无活动配置的店铺本就没有广告数据，直接跳过；</li>
 *   <li>默认回补近 7 天（含当天）：SP-API 归因约 72h 延迟，逐天 upsert 使修正数
 *       次日自动覆盖，当天部分数据次日自愈；</li>
 *   <li>落库粒度为 campaign/天（唯一键 {@code uk_shop_campaign_date}），趋势端点
 *       按天求和因此天然支持该粒度；adType 由本地 campaign_ext 表回填
 *       （API 行本身不带类型），供趋势按类型过滤；</li>
 *   <li>单店铺异常 catch 后继续下一家；表未建等异常降级记 warn，不阻断其他店铺。</li>
 * </ul>
 */
@Slf4j
@Component
public class AdReportSyncScheduler {

    /** 默认回补天数：覆盖 SP-API 约 72h 归因延迟窗口 */
    private static final int DEFAULT_DAYS = 7;
    /** 回补上限：控制单次调度的 API 调用量（店铺数 × 天数） */
    private static final int MAX_DAYS = 30;
    /** 单次 keyset 查询的店铺/活动页大小上限，防止配置错误把整表重新拉回内存。 */
    private static final int MAX_PAGE_SIZE = 1_000;

    @Value("${advertising.scheduler.shop-page-size:500}")
    private int shopPageSize = 500;

    @Value("${advertising.scheduler.campaign-page-size:500}")
    private int campaignPageSize = 500;

    @Autowired
    private AdCampaignExtMapper adCampaignExtMapper;

    @Autowired
    private AdDailyReportMapper adDailyReportMapper;

    @Autowired
    private AdvertisingApiClient advertisingApiClient;

    /**
     * mock profile 不创建配置型凭证提供器；真实 profile 下用于发现尚无本地活动记录的店铺。
     */
    @Autowired(required = false)
    private AdvertisingCredentialProvider advertisingCredentialProvider;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /**
     * 每天 02:00 全量同步各店铺近 7 天广告日报（cron: 0 0 2 * * *）。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void syncDaily() {
        distributedJobLock.runWithLock("amz:sched:ad-report-sync", 55 * 60L, () -> syncAllShops(DEFAULT_DAYS));
    }

    /**
     * 同步全部有广告活动配置的店铺（定时与手动触发共用入口）。
     *
     * @param days 回补天数（非法值按默认 7 天，上限 30 天）
     * @return 本次落库（insert + update）的日报行数
     */
    public int syncAllShops(int days) {
        return syncAllShopsWithSummary(days).upserted();
    }

    /**
     * 同步全部店铺并返回可观测结果。单个店铺失败不会阻断其他店铺，
     * 但必须计入 failed，避免调用方把部分失败误判为全部成功。
     */
    public SyncSummary syncAllShopsWithSummary(int days) {
        List<Long> shopIds = distinctShopIds();
        if (shopIds.isEmpty()) {
            log.info("广告日报同步：无广告活动配置的店铺，跳过");
            return SyncSummary.empty();
        }
        log.info("广告日报同步开始：shopCount={} days={}", shopIds.size(), days);
        SyncSummary total = SyncSummary.empty();
        for (Long shopId : shopIds) {
            try {
                total = total.merge(syncShopReportsWithSummary(shopId, days));
            } catch (Exception e) {
                log.error("广告日报同步失败 shopId={}", shopId, e);
                total = total.merge(SyncSummary.failedAttempt());
            }
        }
        log.info("广告日报同步完成：attempted={} succeeded={} failed={} skipped={} upserted={} metadataWarnings={}",
                total.attempted(), total.succeeded(), total.failed(), total.skipped(), total.upserted(),
                total.metadataWarnings());
        return total;
    }

    /**
     * 同步单个店铺近 N 天广告日报（幂等 upsert，可重复执行做归因修正回补）。
     *
     * @param shopId 店铺 ID（null 直接返回 0）
     * @param days   回补天数（非法值按默认 7 天，上限 30 天）
     * @return 落库行数
     */
    public int syncShopReports(Long shopId, int days) {
        return syncShopReportsWithSummary(shopId, days).upserted();
    }

    /**
     * 同步单个店铺并返回可观测结果。
     */
    public SyncSummary syncShopReportsWithSummary(Long shopId, int days) {
        if (shopId == null) {
            return SyncSummary.skippedAttempt();
        }
        int n = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(n - 1L);
        boolean metadataFailed = syncCampaignMetadata(shopId);
        Map<String, String> adTypeMap = loadAdTypeMap(shopId);
        int saved;
        try {
            List<AdReport> rows = advertisingApiClient.getReports(shopId, start.toString(), end.toString());
            saved = upsertRows(shopId, aggregateDailyRows(rows), adTypeMap);
        } catch (Exception e) {
            log.error("广告日报同步失败 shopId={} start={} end={}", shopId, start, end, e);
            return SyncSummary.failedAttempt();
        }
        log.info("广告日报同步完成 shopId={} days={} upserted={} metadataWarnings={}",
                shopId, n, saved, metadataFailed ? 1 : 0);
        return SyncSummary.successfulAttempt(saved, metadataFailed ? 1 : 0);
    }

    /**
     * 同步结果。succeeded 表示成功完成 API 拉取与落库流程的店铺数，
     * upserted 表示实际写入或更新的日报行数；两者口径不同。
     */
    public record SyncSummary(int attempted, int succeeded, int failed, int skipped, int upserted,
                              int metadataWarnings) {

        public static SyncSummary empty() {
            return new SyncSummary(0, 0, 0, 0, 0, 0);
        }

        public static SyncSummary successfulAttempt(int upserted) {
            return successfulAttempt(upserted, 0);
        }

        public static SyncSummary successfulAttempt(int upserted, int metadataWarnings) {
            return new SyncSummary(1, 1, 0, 0, upserted, metadataWarnings);
        }

        public static SyncSummary failedAttempt() {
            return new SyncSummary(1, 0, 1, 0, 0, 0);
        }

        public static SyncSummary skippedAttempt() {
            return new SyncSummary(0, 0, 0, 1, 0, 0);
        }

        public SyncSummary merge(SyncSummary other) {
            if (other == null) {
                return this;
            }
            return new SyncSummary(
                    attempted + other.attempted,
                    succeeded + other.succeeded,
                    failed + other.failed,
                    skipped + other.skipped,
                    upserted + other.upserted,
                    metadataWarnings + other.metadataWarnings);
        }
    }
    /**
     * 从 Advertising API 刷新活动元数据。
     * <p>
     * 元数据失败不阻断日报同步：报表接口可能已授权，而活动列表接口暂不可用；
     * 错误会记录日志，调用方可从同步统计识别。活动本身不返回性能字段，
     * 因此 upsert 只覆盖名称、类型、预算、竞价策略和状态。
     */
    private boolean syncCampaignMetadata(Long shopId) {
        List<AdCampaign> campaigns;
        try {
            campaigns = advertisingApiClient.listCampaigns(shopId);
        } catch (Exception e) {
            log.error("广告活动元数据同步失败 shopId={}", shopId, e);
            return true;
        }
        if (campaigns == null || campaigns.isEmpty()) {
            return false;
        }
        boolean failed = false;
        for (AdCampaign campaign : campaigns) {
            if (campaign == null || !hasText(campaign.getCampaignId())) {
                continue;
            }
            String campaignId = campaign.getCampaignId();
            String adType = hasText(campaign.getCampaignType()) ? campaign.getCampaignType() : "SP";
            AdCampaignExt row = new AdCampaignExt();
            row.setShopId(shopId);
            row.setCampaignId(campaignId);
            row.setCampaignName(hasText(campaign.getName()) ? campaign.getName() : campaignId);
            row.setAdType(adType);
            row.setCampaignType(adType);
            row.setBudget(campaign.getDailyBudget());
            row.setBudgetType("DAILY");
            row.setBiddingStrategy(campaign.getBiddingStrategy());
            row.setStatus(hasText(campaign.getState()) ? campaign.getState() : "UNKNOWN");
            try {
                adCampaignExtMapper.upsertMetadata(row);
            } catch (Exception e) {
                log.error("广告活动元数据落库失败 shopId={} campaignId={}", shopId, campaignId, e);
                failed = true;
            }
        }
        return failed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 将广告组粒度 DAILY 行按 (campaignId, reportDate) 聚合为日报行。
     * <p>数据库唯一键不包含 adGroupId；如果不先聚合，同一活动同一天会有多行，
     * 最终只能覆盖其中一行，导致报表少计。API 客户端已保证 DAILY 行带 reportDate。
     */
    private static List<AdReport> aggregateDailyRows(List<AdReport> rows) {
        Map<String, AdReport> aggregated = new LinkedHashMap<>();
        if (rows == null) {
            return List.of();
        }
        for (AdReport row : rows) {
            if (row == null || row.getCampaignId() == null || row.getCampaignId().isBlank()) {
                continue;
            }
            if (row.getReportDate() == null) {
                log.warn("广告日报同步：忽略缺少 reportDate 的行 campaignId={}", row.getCampaignId());
                continue;
            }
            String key = row.getReportDate() + "\u0000" + row.getCampaignId();
            AdReport target = aggregated.computeIfAbsent(key, ignored -> {
                AdReport value = new AdReport();
                value.setCampaignId(row.getCampaignId());
                value.setReportDate(row.getReportDate());
                return value;
            });
            target.setImpressions(sum(target.getImpressions(), row.getImpressions()));
            target.setClicks(sum(target.getClicks(), row.getClicks()));
            target.setCost(sum(target.getCost(), row.getCost()));
            target.setSales(sum(target.getSales(), row.getSales()));
            target.setOrders(sum(target.getOrders(), row.getOrders()));
            target.setUnits(sum(target.getUnits(), row.getUnits()));
        }
        return new ArrayList<>(aggregated.values());
    }

    /**
     * 按 (shopId, campaignId, reportDate) 幂等落库。
     */
    private int upsertRows(Long shopId, List<AdReport> rows, Map<String, String> adTypeMap) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (AdReport r : rows) {
            if (r == null || r.getCampaignId() == null || r.getReportDate() == null) {
                continue;
            }
            AdDailyReport row = new AdDailyReport();
            row.setShopId(shopId);
            row.setCampaignId(r.getCampaignId());
            row.setAdType(adTypeMap.get(r.getCampaignId()));
            row.setReportDate(r.getReportDate());
            row.setImpressions(r.getImpressions());
            row.setClicks(r.getClicks());
            row.setCost(r.getCost());
            row.setSales(r.getSales());
            row.setOrders(r.getOrders());
            row.setUnits(r.getUnits());
            row.setAcos(acosOf(r));
            row.setRoas(roasOf(r));
            row.setCtr(ctrOf(r));
            row.setCr(crOf(r));
            row.setCpc(cpcOf(r));
            try {
                if (adDailyReportMapper.upsert(row) > 0) {
                    saved++;
                }
            } catch (Exception e) {
                log.error("广告日报落库失败 shopId={} date={} campaignId={}",
                        shopId, r.getReportDate(), r.getCampaignId(), e);
            }
        }
        return saved;
    }

    private static Long sum(Long left, Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    private static Integer sum(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    private static BigDecimal sum(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right);
    }

    /**
     * 本店铺 campaignId → adType 映射（活动配置表自包含，无跨服务调用）。
     */
    private Map<String, String> loadAdTypeMap(Long shopId) {
        Map<String, String> map = new HashMap<>();
        int limit = boundedPageSize(campaignPageSize);
        long cursor = Long.MIN_VALUE;
        try {
            while (true) {
                List<AdCampaignExt> campaigns = adCampaignExtMapper.selectAdTypePage(shopId, cursor, limit);
                if (campaigns == null || campaigns.isEmpty()) {
                    break;
                }
                long nextCursor = cursor;
                for (AdCampaignExt c : campaigns) {
                    if (c == null) {
                        continue;
                    }
                    if (c.getId() != null && c.getId() > nextCursor) {
                        nextCursor = c.getId();
                    }
                    if (c.getCampaignId() != null && c.getAdType() != null) {
                        map.put(c.getCampaignId(), c.getAdType());
                    }
                }
                if (campaigns.size() < limit) {
                    break;
                }
                if (nextCursor <= cursor) {
                    log.error("广告日报同步：活动类型分页游标未推进 shopId={} cursor={} limit={}",
                            shopId, cursor, limit);
                    break;
                }
                cursor = nextCursor;
            }
        } catch (Exception e) {
            log.error("广告日报同步：加载活动类型映射失败 shopId={}，adType 将留空", shopId, e);
        }
        return map;
    }

    private static int boundedPageSize(int configured) {
        return Math.max(1, Math.min(configured, MAX_PAGE_SIZE));
    }

    /**
     * 有广告活动配置的店铺 ID 去重列表。
     */
    private List<Long> distinctShopIds() {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        int limit = boundedPageSize(shopPageSize);
        long cursor = Long.MIN_VALUE;
        try {
            while (true) {
                List<Long> page = adCampaignExtMapper.selectShopIdsAfter(cursor, limit);
                if (page == null || page.isEmpty()) {
                    break;
                }
                long nextCursor = cursor;
                for (Long shopId : page) {
                    if (shopId == null) {
                        continue;
                    }
                    ids.add(shopId);
                    if (shopId > nextCursor) {
                        nextCursor = shopId;
                    }
                }
                if (page.size() < limit) {
                    break;
                }
                if (nextCursor <= cursor) {
                    log.error("广告日报同步：店铺分页游标未推进 cursor={} limit={}", cursor, limit);
                    break;
                }
                cursor = nextCursor;
            }
        } catch (Exception e) {
            log.error("广告日报同步：查询本地活动店铺列表失败", e);
        }

        if (advertisingCredentialProvider != null) {
            try {
                Set<Long> configuredShopIds = advertisingCredentialProvider.configuredShopIds();
                if (configuredShopIds != null) {
                    configuredShopIds.stream().filter(Objects::nonNull).forEach(ids::add);
                }
            } catch (Exception e) {
                log.warn("广告日报同步：读取配置凭证店铺列表失败", e);
            }
        }
        return new ArrayList<>(ids);
    }

    private static BigDecimal ctrOf(AdReport r) {
        if (r.getCtr() != null) {
            return r.getCtr();
        }
        if (r.getImpressions() == null || r.getImpressions() <= 0 || r.getClicks() == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(r.getClicks())
                .divide(BigDecimal.valueOf(r.getImpressions()), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal crOf(AdReport r) {
        if (r.getCr() != null) {
            return r.getCr();
        }
        if (r.getClicks() == null || r.getClicks() <= 0 || r.getOrders() == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(r.getOrders())
                .divide(BigDecimal.valueOf(r.getClicks()), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal cpcOf(AdReport r) {
        if (r.getCpc() != null) {
            return r.getCpc();
        }
        if (r.getClicks() == null || r.getClicks() <= 0 || r.getCost() == null) {
            return BigDecimal.ZERO;
        }
        return r.getCost().divide(BigDecimal.valueOf(r.getClicks()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal acosOf(AdReport r) {
        if (r.getAcos() != null) {
            return r.getAcos();
        }
        if (r.getCost() == null || r.getSales() == null
                || r.getSales().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return r.getCost().divide(r.getSales(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal roasOf(AdReport r) {
        if (r.getRoas() != null) {
            return r.getRoas();
        }
        if (r.getSales() == null || r.getCost() == null
                || r.getCost().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return r.getSales().divide(r.getCost(), 2, RoundingMode.HALF_UP);
    }
}
