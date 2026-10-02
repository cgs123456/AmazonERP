package com.amz.service.impl;

import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
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
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * 关键词趋势一次最多返回多少个点。折线要的是连续序列，所以不翻页，但必须有上限：
     * 抓取是反复追加的，不设上限时一次趋势查询会随运行时长越来越贵。
     */
    static final int MAX_RANK_TREND_POINTS = 200;

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
    public PageResult<NegativeReviewAlert> listNegativeReviewAlerts(Long shopId, String status, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        Long cursorId = req.cursorId();
        LambdaQueryWrapper<NegativeReviewAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NegativeReviewAlert::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(NegativeReviewAlert::getStatus, status);
        }
        if (cursorId != null) {
            wrapper.lt(NegativeReviewAlert::getId, cursorId);
        }
        wrapper.orderByDesc(NegativeReviewAlert::getId).last("LIMIT " + req.probeSize());
        List<NegativeReviewAlert> rows = reviewAlertMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), a -> PageRequest.encodeCursor(a.getId()));
    }

    @Override
    public boolean handleNegativeReviewAlert(Long alertId) {
        NegativeReviewAlert alert = reviewAlertMapper.selectById(alertId);
        // 不存在与越权同一句文案：这条端点只有 alertId，能区分两者就成了告警 ID 探针。
        if (alert == null) {
            throw new CodeErrorException("差评告警不存在或无权访问");
        }
        // V1 DDL 是 shop_id BIGINT NOT NULL，出现 null 就是脏数据；原来的
        // 「shopId != null && !isShopAllowed」把脏数据当成免检，等于谁都能改。
        // 这条端点上也没有 @ShopScoped（alertId 不是 shopId，切面解析不到），
        // 所以服务内的逐行严格判定是唯一一道防线。
        if (!UserContext.isShopAllowedStrict(alert.getShopId())) {
            log.warn("差评告警处理越权拦截：alertId={}, alertShopId={}, 已授权店铺={}",
                    alertId, alert.getShopId(), UserContext.getShops());
            throw new CodeErrorException("差评告警不存在或无权访问");
        }
        if (!"NEW".equals(alert.getStatus())) {
            throw new CodeErrorException("该告警已经是 " + alert.getStatus() + "，没有再次处理");
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
    public PageResult<HijackAlert> listHijackAlerts(Long shopId, String status, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        Long cursorId = req.cursorId();
        LambdaQueryWrapper<HijackAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HijackAlert::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(HijackAlert::getStatus, status);
        }
        if (cursorId != null) {
            wrapper.lt(HijackAlert::getId, cursorId);
        }
        wrapper.orderByDesc(HijackAlert::getId).last("LIMIT " + req.probeSize());
        List<HijackAlert> rows = hijackAlertMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), a -> PageRequest.encodeCursor(a.getId()));
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
        // capture_time 是 'yyyy-MM-dd HH:mm:ss' 字符串，字典序即时间序，所以可以按它倒着
        // 取最近的若干个点；返回前再反转成升序，调用方画折线不需要自己猜方向。
        LambdaQueryWrapper<KeywordRankRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordRankRecord::getShopId, shopId)
                .eq(KeywordRankRecord::getKeyword, keyword)
                .eq(KeywordRankRecord::getAsin, asin)
                .orderByDesc(KeywordRankRecord::getCaptureTime)
                .orderByDesc(KeywordRankRecord::getId)
                .last("LIMIT " + MAX_RANK_TREND_POINTS);
        List<KeywordRankRecord> rows = rankMapper.selectList(wrapper);
        List<KeywordRankRecord> ascending = new ArrayList<>(rows);
        Collections.reverse(ascending);
        return ascending;
    }
}
