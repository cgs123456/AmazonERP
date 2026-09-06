package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdKeyword;
import com.amz.model.BidSchedule;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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

    @Autowired
    private BidScheduleMapper bidScheduleMapper;

    @Autowired
    private AdKeywordMapper adKeywordMapper;

    @Autowired
    private AdvertisingApiClient advertisingApiClient;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /**
     * 每小时整点执行分时调价（cron: 0 0 * * * ?）。
     * 分布式锁：调价按「当前价 × 倍率」计算，多实例双跑会叠加调价，必须互斥。
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
     */
    private void applyRule(BidSchedule rule) {
        try {
            List<AdKeyword> keywords = advertisingApiClient.listKeywords(rule.getShopId(), rule.getCampaignId());
            if (keywords == null || keywords.isEmpty()) {
                return;
            }
            Map<String, BigDecimal> baseBids = loadBaseBids(rule.getShopId(), rule.getCampaignId());
            int success = 0;
            for (AdKeyword kw : keywords) {
                if (kw.getBid() == null) {
                    continue;
                }
                BigDecimal base = baseBids.get(baseKey(kw));
                if (base == null) {
                    base = kw.getBid();
                    adoptBaseBid(rule.getShopId(), rule.getCampaignId(), kw, base);
                    baseBids.put(baseKey(kw), base);
                }
                BigDecimal newBid = base.multiply(rule.getMultiplier())
                        .setScale(2, RoundingMode.HALF_UP);
                // 广告 API 竞价下限 0.02，上限 1000
                if (newBid.compareTo(new BigDecimal("0.02")) < 0) {
                    newBid = new BigDecimal("0.02");
                } else if (newBid.compareTo(new BigDecimal("1000")) > 0) {
                    newBid = new BigDecimal("1000");
                }
                advertisingApiClient.updateKeywordBid(kw.getId(), newBid);
                success++;
            }
            log.info("分时调价完成：shopId={} campaignId={} multiplier={} 调整 {} 个关键词",
                    rule.getShopId(), rule.getCampaignId(), rule.getMultiplier(), success);
        } catch (Exception e) {
            log.error("分时调价失败：ruleId={}", rule.getId(), e);
        }
    }

    private static String baseKey(AdKeyword kw) {
        return String.valueOf(kw.getKeyword()) + "\0" + String.valueOf(kw.getMatchType());
    }

    /**
     * 一次性加载本活动已持久化的基准价（key 含 matchType，区分同词不同匹配）。
     */
    private Map<String, BigDecimal> loadBaseBids(Long shopId, String campaignId) {
        Map<String, BigDecimal> map = new HashMap<>();
        try {
            LambdaQueryWrapper<AdKeyword> qw = new LambdaQueryWrapper<AdKeyword>()
                    .eq(AdKeyword::getShopId, shopId)
                    .eq(campaignId != null, AdKeyword::getCampaignId, campaignId);
            for (AdKeyword row : adKeywordMapper.selectList(qw)) {
                if (row != null && row.getBaseBid() != null) {
                    map.put(baseKey(row), row.getBaseBid());
                }
            }
        } catch (Exception e) {
            // amz_ad_keyword.base_bid 列尚未迁移时降级为空映射，
            // 本轮按认领逻辑重建（不阻断调价，列补齐后自动持久化）
            log.warn("基准价加载失败 shopId={} campaignId={}，本轮重认领", shopId, campaignId, e);
        }
        return map;
    }

    /**
     * 认领基准价并持久化（行不存在则插入最小行，存在但 base 为空则补写）。
     * 持久化失败仅记 warn，不阻断本次调价。
     */
    private void adoptBaseBid(Long shopId, String campaignId, AdKeyword kw, BigDecimal base) {
        try {
            LambdaQueryWrapper<AdKeyword> qw = new LambdaQueryWrapper<AdKeyword>()
                    .eq(AdKeyword::getShopId, shopId);
            if (campaignId != null) {
                qw.eq(AdKeyword::getCampaignId, campaignId);
            } else {
                qw.isNull(AdKeyword::getCampaignId);
            }
            if (kw.getKeyword() != null) {
                qw.eq(AdKeyword::getKeyword, kw.getKeyword());
            } else {
                qw.isNull(AdKeyword::getKeyword);
            }
            AdKeyword row = adKeywordMapper.selectOne(qw);
            if (row == null) {
                row = new AdKeyword();
                row.setShopId(shopId);
                row.setCampaignId(campaignId);
                row.setKeyword(kw.getKeyword());
                row.setMatchType(kw.getMatchType());
                row.setBid(kw.getBid());
                row.setBaseBid(base);
                row.setState(kw.getState());
                adKeywordMapper.insert(row);
            } else if (row.getBaseBid() == null) {
                row.setBaseBid(base);
                adKeywordMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("基准价认领持久化失败 shopId={} keyword={}", shopId, kw.getKeyword(), e);
        }
    }
}
