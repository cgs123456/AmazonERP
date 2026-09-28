package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.client.AdvertisingApiException;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdKeyword;
import com.amz.model.BidSchedule;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分时调价执行器。
 * <p>
 * 每小时整点触发（cron: 0 0 * * * ?），扫描启用的 {@link BidSchedule}，
 * 对当前小时命中的规则，将倍率下发到 Advertising API 调整关键词竞价。
 * <p>
 * 示例效果：
 * <ul>
 *   <li>0-6 点：multiplier=0.7，竞价 ×0.7（避开低转化时段）</li>
 *   <li>20-23 点：multiplier=1.5，竞价 ×1.5（抢占晚高峰流量）</li>
 * </ul>
 */
@Slf4j
@Component
public class BidScheduleExecutor {

    private static final int KEY_BATCH_SIZE = 200;
    private static final long MAX_BID_UPDATE_INTERVAL_MS = 10_000L;
    private static final BigDecimal MIN_BID = new BigDecimal("0.02");
    private static final BigDecimal MAX_BID = new BigDecimal("1000");

    @Autowired
    private BidScheduleMapper bidScheduleMapper;

    @Autowired
    private AdKeywordMapper adKeywordMapper;

    @Autowired
    private AdvertisingApiClient advertisingApiClient;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /**
     * 单次关键词调价之间的最小间隔，默认 0；真实账号可按 API 配额配置。
     */
    @Value("${advertising.scheduler.bid-update-interval-ms:0}")
    private long bidUpdateIntervalMs = 0L;

    /**
     * 每小时整点执行分时调价（cron: 0 0 * * * ?）。
     * 分布式锁：调价按「基准价 × 倍率」计算，多实例双跑会叠加调价，必须互斥。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void executeHourly() {
        distributedJobLock.runWithLock("amz:sched:bid-executor", 55 * 60L, this::doExecuteHourly);
    }

    private void doExecuteHourly() {
        int currentHour = LocalDateTime.now().getHour();
        List<BidSchedule> activeRules = bidScheduleMapper.selectEnabledByHour(currentHour);
        if (activeRules == null || activeRules.isEmpty()) {
            log.debug("分时调价：当前小时 {} 无命中规则", currentHour);
            return;
        }
        log.info("分时调价：小时 {} 命中 {} 条规则", currentHour, activeRules.size());
        for (BidSchedule rule : activeRules) {
            applyRule(rule);
        }
    }

    /**
     * 应用单条调价规则：查询该规则作用范围下的关键词，计算新竞价并下发。
     * <p>
     * 新竞价一律按 基准价 × 倍率 计算（基准价首次触达时认领并持久化到
     * amz_ad_keyword.base_bid），禁止在上一小时结果上连乘——否则 1.5x 规则
     * 几小时内即指数爆炸烧费。
     * <p>
     * 基准价查询或持久化失败时整条规则 fail-closed，宁可本轮不调价，也不能在
     * 无法持久化基准价的情况下继续改价，否则下一轮会把已改价格再次作为基准。
     */
    private void applyRule(BidSchedule rule) {
        try {
            List<AdKeyword> keywords = advertisingApiClient.listKeywords(rule.getShopId(), rule.getCampaignId());
            if (keywords == null || keywords.isEmpty()) {
                return;
            }
            PreparedBaseBids prepared = prepareBaseBids(rule, keywords);
            if (prepared.keywords().isEmpty()) {
                log.warn("分时调价跳过：shopId={} campaignId={} 无可用于调价的有效关键词",
                        rule.getShopId(), rule.getCampaignId());
                return;
            }

            persistBaseBids(rule.getShopId(), prepared.inserts(), prepared.updates());

            int success = 0;
            int failed = 0;
            int skipped = prepared.skipped();
            List<AdKeyword> updateRows = prepared.keywords();
            for (int i = 0; i < updateRows.size(); i++) {
                AdKeyword kw = updateRows.get(i);
                String campaignId = effectiveCampaignId(rule, kw);
                BigDecimal base = prepared.baseBids().get(baseKey(campaignId, kw.getKeyword(), kw.getMatchType()));
                if (base == null) {
                    skipped++;
                    continue;
                }
                BigDecimal newBid = clampBid(base.multiply(rule.getMultiplier())
                        .setScale(2, RoundingMode.HALF_UP));
                try {
                    if (advertisingApiClient.updateKeywordBid(rule.getShopId(), kw.getId(), newBid)) {
                        success++;
                    } else {
                        failed++;
                    }
                } catch (AdvertisingApiException e) {
                    failed++;
                    log.warn("关键词调价失败 shopId={} campaignId={} keywordId={} code={}",
                            rule.getShopId(), campaignId, kw.getId(), e.getCode(), e);
                    if ("AD_RATE_LIMITED".equals(e.getCode()) || "AD_AUTH_FAILED".equals(e.getCode())) {
                        int remaining = updateRows.size() - i - 1;
                        skipped += remaining;
                        log.warn("分时调价提前终止：shopId={} campaignId={} code={} remaining={}",
                                rule.getShopId(), campaignId, e.getCode(), remaining);
                        break;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("关键词调价异常 shopId={} campaignId={} keywordId={}",
                            rule.getShopId(), campaignId, kw.getId(), e);
                }
                pauseBetweenUpdates(i, updateRows.size());
            }
            log.info("分时调价完成：shopId={} campaignId={} multiplier={} success={} failed={} skipped={}",
                    rule.getShopId(), rule.getCampaignId(), rule.getMultiplier(), success, failed, skipped);
        } catch (Exception e) {
            log.error("分时调价失败：ruleId={}", rule.getId(), e);
        }
    }

    /**
     * 按活动批量读取本地基准价，并准备首次认领所需的最小写入集合。
     * <p>
     * 以远程关键词为驱动、按 {@code shop_id + campaign_id + keyword + match_type}
     * 分块查询，避免一次扫描整个关键词表。相同词形在不同活动之间不会互相覆盖。
     */
    private PreparedBaseBids prepareBaseBids(BidSchedule rule, List<AdKeyword> remoteKeywords) {
        Map<String, LinkedHashMap<String, AdKeyword>> grouped = new LinkedHashMap<>();
        int skipped = 0;
        for (AdKeyword keyword : remoteKeywords) {
            if (!usableRemoteKeyword(rule, keyword)) {
                skipped++;
                continue;
            }
            String campaignId = effectiveCampaignId(rule, keyword);
            String key = baseKey(campaignId, keyword.getKeyword(), keyword.getMatchType());
            grouped.computeIfAbsent(campaignId, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(key, keyword);
        }

        Map<String, BigDecimal> baseBids = new HashMap<>();
        List<AdKeyword> inserts = new ArrayList<>();
        List<AdKeyword> updates = new ArrayList<>();
        List<AdKeyword> usableKeywords = new ArrayList<>();

        for (Map.Entry<String, LinkedHashMap<String, AdKeyword>> entry : grouped.entrySet()) {
            String campaignId = entry.getKey();
            List<AdKeyword> campaignKeywords = new ArrayList<>(entry.getValue().values());
            usableKeywords.addAll(campaignKeywords);
            for (int from = 0; from < campaignKeywords.size(); from += KEY_BATCH_SIZE) {
                int to = Math.min(from + KEY_BATCH_SIZE, campaignKeywords.size());
                List<AdKeyword> batch = campaignKeywords.subList(from, to);
                List<AdKeyword> localRows = adKeywordMapper.selectList(
                        baseBidLookup(rule.getShopId(), campaignId, batch));
                Map<String, AdKeyword> localByKey = new HashMap<>();
                if (localRows != null) {
                    for (AdKeyword row : localRows) {
                        if (row != null && campaignId.equals(row.getCampaignId())
                                && hasText(row.getKeyword()) && hasText(row.getMatchType())) {
                            localByKey.putIfAbsent(baseKey(campaignId, row.getKeyword(), row.getMatchType()), row);
                        }
                    }
                }

                for (AdKeyword keyword : batch) {
                    String key = baseKey(campaignId, keyword.getKeyword(), keyword.getMatchType());
                    AdKeyword local = localByKey.get(key);
                    if (local != null && local.getBaseBid() != null) {
                        baseBids.put(key, local.getBaseBid());
                        continue;
                    }
                    BigDecimal base = keyword.getBid();
                    if (base == null) {
                        skipped++;
                        continue;
                    }
                    baseBids.put(key, base);
                    if (local == null) {
                        inserts.add(newLocalKeyword(rule.getShopId(), campaignId, keyword, base));
                    } else {
                        local.setBaseBid(base);
                        updates.add(local);
                    }
                }
            }
        }
        return new PreparedBaseBids(List.copyOf(usableKeywords), Map.copyOf(baseBids),
                List.copyOf(inserts), List.copyOf(updates), skipped);
    }

    private void persistBaseBids(Long shopId, List<AdKeyword> inserts, List<AdKeyword> updates) {
        if (!inserts.isEmpty()) {
            adKeywordMapper.insertBaseBidRows(inserts);
        }
        if (!updates.isEmpty()) {
            adKeywordMapper.updateBaseBidsByIds(shopId, updates);
        }
    }

    private LambdaQueryWrapper<AdKeyword> baseBidLookup(Long shopId, String campaignId, List<AdKeyword> batch) {
        return new LambdaQueryWrapper<AdKeyword>()
                .eq(AdKeyword::getShopId, shopId)
                .eq(AdKeyword::getCampaignId, campaignId)
                .and(wrapper -> {
                    boolean first = true;
                    for (AdKeyword keyword : batch) {
                        String text = normalizeKeyword(keyword.getKeyword());
                        String matchType = normalizeMatchType(keyword.getMatchType());
                        if (first) {
                            wrapper.eq(AdKeyword::getKeyword, text)
                                    .eq(AdKeyword::getMatchType, matchType);
                            first = false;
                        } else {
                            wrapper.or(inner -> inner.eq(AdKeyword::getKeyword, text)
                                    .eq(AdKeyword::getMatchType, matchType));
                        }
                    }
                });
    }

    private static AdKeyword newLocalKeyword(Long shopId, String campaignId, AdKeyword remote, BigDecimal base) {
        AdKeyword row = new AdKeyword();
        row.setShopId(shopId);
        row.setCampaignId(campaignId);
        row.setKeyword(remote.getKeyword().trim());
        row.setMatchType(normalizeMatchType(remote.getMatchType()));
        row.setBid(remote.getBid());
        row.setBaseBid(base);
        row.setState(remote.getState());
        return row;
    }

    private static boolean usableRemoteKeyword(BidSchedule rule, AdKeyword keyword) {
        return keyword != null
                && keyword.getId() != null
                && hasText(effectiveCampaignId(rule, keyword))
                && hasText(keyword.getKeyword())
                && hasText(keyword.getMatchType())
                && keyword.getBid() != null;
    }

    private static String effectiveCampaignId(BidSchedule rule, AdKeyword keyword) {
        String campaignId = keyword == null ? null : keyword.getCampaignId();
        return hasText(campaignId) ? campaignId.trim() : (rule == null ? null : rule.getCampaignId());
    }

    private static BigDecimal clampBid(BigDecimal bid) {
        if (bid.compareTo(MIN_BID) < 0) {
            return MIN_BID;
        }
        if (bid.compareTo(MAX_BID) > 0) {
            return MAX_BID;
        }
        return bid;
    }

    private void pauseBetweenUpdates(int index, int size) {
        if (bidUpdateIntervalMs <= 0 || index >= size - 1) {
            return;
        }
        long delay = Math.min(bidUpdateIntervalMs, MAX_BID_UPDATE_INTERVAL_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bid update pacing interrupted", e);
        }
    }

    private static String baseKey(String campaignId, String keyword, String matchType) {
        return String.valueOf(campaignId) + "\0" + normalizeKeyword(keyword) + "\0"
                + normalizeMatchType(matchType);
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMatchType(String matchType) {
        return matchType == null ? "" : matchType.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PreparedBaseBids(List<AdKeyword> keywords,
                                    Map<String, BigDecimal> baseBids,
                                    List<AdKeyword> inserts,
                                    List<AdKeyword> updates,
                                    int skipped) {
    }
}
