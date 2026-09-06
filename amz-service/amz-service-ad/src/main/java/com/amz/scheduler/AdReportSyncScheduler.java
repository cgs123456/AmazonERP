package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.mapper.AdDailyReportMapper;
import com.amz.model.AdCampaignExt;
import com.amz.model.AdDailyReport;
import com.amz.model.AdReport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private AdCampaignExtMapper adCampaignExtMapper;

    @Autowired
    private AdDailyReportMapper adDailyReportMapper;

    @Autowired
    private AdvertisingApiClient advertisingApiClient;

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
        List<Long> shopIds = distinctShopIds();
        if (shopIds.isEmpty()) {
            log.info("广告日报同步：无广告活动配置的店铺，跳过");
            return 0;
        }
        log.info("广告日报同步开始：shopCount={} days={}", shopIds.size(), days);
        int total = 0;
        for (Long shopId : shopIds) {
            try {
                total += syncShopReports(shopId, days);
            } catch (Exception e) {
                log.error("广告日报同步失败 shopId={}", shopId, e);
            }
        }
        log.info("广告日报同步完成：upserted={}", total);
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
        if (shopId == null) {
            return 0;
        }
        int n = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate end = LocalDate.now();
        Map<String, String> adTypeMap = loadAdTypeMap(shopId);
        int saved = 0;
        for (int i = n - 1; i >= 0; i--) {
            LocalDate day = end.minusDays(i);
            try {
                List<AdReport> rows = advertisingApiClient.getReports(shopId, day.toString(), day.toString());
                saved += upsertDay(shopId, day, rows, adTypeMap);
            } catch (Exception e) {
                log.error("广告日报同步失败 shopId={} date={}", shopId, day, e);
            }
        }
        log.info("广告日报同步完成 shopId={} days={} upserted={}", shopId, n, saved);
        return saved;
    }

    /**
     * 单日行落库：按 (shopId, campaignId, reportDate) upsert。
     */
    private int upsertDay(Long shopId, LocalDate day, List<AdReport> rows, Map<String, String> adTypeMap) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (AdReport r : rows) {
            if (r == null) {
                continue;
            }
            LambdaQueryWrapper<AdDailyReport> qw = new LambdaQueryWrapper<AdDailyReport>()
                    .eq(AdDailyReport::getShopId, shopId)
                    .eq(AdDailyReport::getReportDate, day);
            if (r.getCampaignId() == null) {
                qw.isNull(AdDailyReport::getCampaignId);
            } else {
                qw.eq(AdDailyReport::getCampaignId, r.getCampaignId());
            }
            AdDailyReport exist;
            try {
                exist = adDailyReportMapper.selectOne(qw);
            } catch (Exception e) {
                // 表未建等异常：记 warn 后按空处理，上层按日继续
                log.warn("广告日报查询失败 shopId={} date={} campaignId={}", shopId, day, r.getCampaignId(), e);
                continue;
            }
            AdDailyReport row = exist != null ? exist : new AdDailyReport();
            row.setShopId(shopId);
            row.setCampaignId(r.getCampaignId());
            // adType 由本地活动配置表回填（API 行本身不带类型，供趋势按类型过滤）
            row.setAdType(r.getCampaignId() == null ? null : adTypeMap.get(r.getCampaignId()));
            row.setReportDate(day);
            row.setImpressions(r.getImpressions());
            row.setClicks(r.getClicks());
            row.setCost(r.getCost());
            row.setSales(r.getSales());
            row.setOrders(r.getOrders());
            row.setAcos(acosOf(r));
            row.setRoas(roasOf(r));
            row.setCtr(r.getCtr());
            row.setCr(r.getCr());
            row.setCpc(r.getCpc());
            try {
                if (exist != null) {
                    adDailyReportMapper.updateById(row);
                } else {
                    adDailyReportMapper.insert(row);
                }
                saved++;
            } catch (Exception e) {
                log.error("广告日报落库失败 shopId={} date={} campaignId={}", shopId, day, r.getCampaignId(), e);
            }
        }
        return saved;
    }

    /**
     * 本店铺 campaignId → adType 映射（活动配置表自包含，无跨服务调用）。
     */
    private Map<String, String> loadAdTypeMap(Long shopId) {
        Map<String, String> map = new HashMap<>();
        try {
            List<AdCampaignExt> campaigns = adCampaignExtMapper.selectList(
                    new LambdaQueryWrapper<AdCampaignExt>().eq(AdCampaignExt::getShopId, shopId));
            if (campaigns == null) {
                return map;
            }
            for (AdCampaignExt c : campaigns) {
                if (c != null && c.getCampaignId() != null && c.getAdType() != null) {
                    map.put(c.getCampaignId(), c.getAdType());
                }
            }
        } catch (Exception e) {
            log.warn("广告日报同步：加载活动类型映射失败 shopId={}，adType 将留空", shopId, e);
        }
        return map;
    }

    /**
     * 有广告活动配置的店铺 ID 去重列表。
     */
    private List<Long> distinctShopIds() {
        List<Long> ids = new ArrayList<>();
        try {
            List<Object> objs = adCampaignExtMapper.selectObjs(
                    new QueryWrapper<AdCampaignExt>().select("DISTINCT shop_id"));
            if (objs == null) {
                return ids;
            }
            for (Object o : objs) {
                if (o == null) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(String.valueOf(o)));
                } catch (NumberFormatException e) {
                    log.warn("广告日报同步：忽略非法 shop_id={}", o);
                }
            }
        } catch (Exception e) {
            log.warn("广告日报同步：查询店铺列表失败", e);
        }
        return ids;
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
