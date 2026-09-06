package com.amz.service.impl;

import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import com.amz.context.UserContext;
import com.amz.service.OpsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 运营工具服务实现。
 * 生产环境应调用 SP-API / 第三方爬虫抓取真实数据，此处为模拟实现。
 */
@Slf4j
@Service
public class OpsServiceImpl implements OpsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private NegativeReviewAlertMapper reviewAlertMapper;

    @Autowired
    private HijackAlertMapper hijackAlertMapper;

    @Autowired
    private KeywordRankRecordMapper rankMapper;

    // Environment 刻意 required=false：纯 Mockito 单元测试无 Spring 上下文时为 null，
    // 此时放行 mock 生成逻辑，保持既有测试语义；生产按 profile 显式放行。
    @Autowired(required = false)
    private Environment environment;

    /**
     * 模拟数据生成逻辑是否允许执行：仅 mock profile 放行，防止生产经 HTTP 误触写入假告警。
     * （定时调度器本身已是 mock profile 限定，此处是 controller 直调的第二道闸。）
     */
    private boolean mockGeneratorsAllowed() {
        if (environment == null) {
            return true;
        }
        return environment.acceptsProfiles(Profiles.of("mock"));
    }

    @Override
    public int scanNegativeReviews(Long shopId) {
        if (!mockGeneratorsAllowed()) {
            log.warn("模拟差评扫描仅限 mock 环境，生产环境拒绝执行 shopId={}", shopId);
            return 0;
        }
        // 模拟：拉取某 ASIN 最新评论，≤3 星则告警
        NegativeReviewAlert alert = new NegativeReviewAlert();
        alert.setShopId(shopId);
        alert.setAsin("B0" + ThreadLocalRandom.current().nextInt(1000000, 9999999));
        alert.setReviewId("R" + System.currentTimeMillis());
        alert.setRating(2);
        alert.setTitle("Product broke after one week");
        alert.setContent("Used it for a week and it stopped working. Very disappointed.");
        alert.setReviewer("John D.");
        alert.setStatus("NEW");
        reviewAlertMapper.insert(alert);
        return 1;
    }

    @Override
    public List<NegativeReviewAlert> listNegativeReviewAlerts(Long shopId, String status) {
        LambdaQueryWrapper<NegativeReviewAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NegativeReviewAlert::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(NegativeReviewAlert::getStatus, status);
        }
        wrapper.orderByDesc(NegativeReviewAlert::getId);
        return reviewAlertMapper.selectList(wrapper);
    }

    @Override
    public boolean handleNegativeReviewAlert(Long alertId) {
        NegativeReviewAlert alert = reviewAlertMapper.selectById(alertId);
        if (alert == null) {
            return false;
        }
        // 归属校验：有 shopId 的告警必须属于当前用户授权店铺（无 shopId 的历史数据放行，保持兼容）
        if (alert.getShopId() != null && !UserContext.isShopAllowed(alert.getShopId())) {
            log.warn("差评告警处理越权拦截：alertId={}, alertShopId={}", alertId, alert.getShopId());
            return false;
        }
        alert.setStatus("HANDLED");
        reviewAlertMapper.updateById(alert);
        return true;
    }

    @Override
    public int scanHijackers(Long shopId) {
        if (!mockGeneratorsAllowed()) {
            log.warn("模拟跟卖扫描仅限 mock 环境，生产环境拒绝执行 shopId={}", shopId);
            return 0;
        }
        // 模拟：检测到其他卖家挂卖
        HijackAlert alert = new HijackAlert();
        alert.setShopId(shopId);
        alert.setAsin("B0" + ThreadLocalRandom.current().nextInt(1000000, 9999999));
        alert.setHijackerSellerId("A" + System.currentTimeMillis());
        alert.setHijackerName("Competitor Seller");
        alert.setHijackPrice(new BigDecimal("19.99"));
        alert.setBuyBoxTaken(ThreadLocalRandom.current().nextBoolean());
        alert.setStatus("NEW");
        hijackAlertMapper.insert(alert);
        return 1;
    }

    @Override
    public List<HijackAlert> listHijackAlerts(Long shopId, String status) {
        LambdaQueryWrapper<HijackAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HijackAlert::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(HijackAlert::getStatus, status);
        }
        wrapper.orderByDesc(HijackAlert::getId);
        return hijackAlertMapper.selectList(wrapper);
    }

    @Override
    public int captureKeywordRanks(Long shopId) {
        if (!mockGeneratorsAllowed()) {
            log.warn("模拟排名抓取仅限 mock 环境，生产环境拒绝执行 shopId={}", shopId);
            return 0;
        }
        // 模拟：抓取 3 个关键词的当前排名快照
        String[] keywords = {"wireless earbuds", "bluetooth headphone", "noise cancelling"};
        String asin = "B0123456789";
        String now = LocalDateTime.now().format(FMT);
        for (String kw : keywords) {
            KeywordRankRecord r = new KeywordRankRecord();
            r.setShopId(shopId);
            r.setKeyword(kw);
            r.setAsin(asin);
            r.setRank(ThreadLocalRandom.current().nextInt(1, 60));
            r.setMarketplace("US");
            r.setCaptureTime(now);
            rankMapper.insert(r);
        }
        return keywords.length;
    }

    @Override
    public List<KeywordRankRecord> getRankTrend(Long shopId, String keyword, String asin) {
        LambdaQueryWrapper<KeywordRankRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordRankRecord::getShopId, shopId)
                .eq(KeywordRankRecord::getKeyword, keyword)
                .eq(KeywordRankRecord::getAsin, asin)
                .orderByAsc(KeywordRankRecord::getCaptureTime);
        return rankMapper.selectList(wrapper);
    }
}
