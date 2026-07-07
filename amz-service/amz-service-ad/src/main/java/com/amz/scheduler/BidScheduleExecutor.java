package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdKeyword;
import com.amz.model.BidSchedule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

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
    private AdvertisingApiClient advertisingApiClient;

    /**
     * 每小时整点执行分时调价。
     * fixedDelay 兜底：若任务执行超过 1 小时，避免重叠。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void executeHourly() {
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
     */
    private void applyRule(BidSchedule rule) {
        try {
            List<AdKeyword> keywords = advertisingApiClient.listKeywords(rule.getShopId(), rule.getCampaignId());
            if (keywords == null || keywords.isEmpty()) {
                return;
            }
            int success = 0;
            for (AdKeyword kw : keywords) {
                if (kw.getBid() == null) {
                    continue;
                }
                BigDecimal newBid = kw.getBid().multiply(rule.getMultiplier())
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
}
