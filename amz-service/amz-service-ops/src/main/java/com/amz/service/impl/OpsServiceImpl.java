package com.amz.service.impl;

import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import com.amz.service.OpsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Override
    public int scanNegativeReviews(Long shopId) {
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
        alert.setStatus("HANDLED");
        reviewAlertMapper.updateById(alert);
        return true;
    }

    @Override
    public int scanHijackers(Long shopId) {
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
