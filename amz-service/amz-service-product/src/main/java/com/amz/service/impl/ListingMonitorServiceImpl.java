package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.*;
import com.amz.model.*;
import com.amz.service.ListingMonitorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Listing 健康监控服务实现。
 * <p>
 * 覆盖：健康度检查 / 变更日志 / 关键词排名追踪 / 竞品监控 / BuyBox 监控
 */
@Slf4j
@Service
public class ListingMonitorServiceImpl implements ListingMonitorService {

    @Autowired
    private ListingHealthMapper listingHealthMapper;
    @Autowired
    private ListingChangeLogMapper listingChangeLogMapper;
    @Autowired
    private KeywordRankingMapper keywordRankingMapper;
    @Autowired
    private CompetitorMonitorMapper competitorMonitorMapper;
    @Autowired
    private BuyBoxMapper buyBoxMapper;

    // ==================== Listing 健康度 ====================

    @Override
    public ListingHealth saveHealthCheck(ListingHealth health) {
        if (health.getShopId() == null || health.getAsin() == null) {
            throw new AttrIsNullException("店铺ID和ASIN不能为空");
        }
        if (health.getCheckTime() == null) health.setCheckTime(LocalDateTime.now());
        // 查重更新
        LambdaQueryWrapper<ListingHealth> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingHealth::getShopId, health.getShopId())
               .eq(ListingHealth::getAsin, health.getAsin());
        ListingHealth exist = listingHealthMapper.selectOne(wrapper);
        if (exist != null) {
            health.setId(exist.getId());
            listingHealthMapper.updateById(health);
        } else {
            listingHealthMapper.insert(health);
        }
        return health;
    }

    @Override
    public ListingHealth checkListing(Long shopId, String asin, String title, String bullets,
                                      String description, Integer imageCount, String searchTerms, String status) {
        int score = 100;
        StringBuilder issues = new StringBuilder();

        boolean titleOk = title != null && title.length() >= 80 && title.length() <= 200;
        if (!titleOk) { score -= 15; issues.append("标题长度需80-200字符; "); }

        boolean bulletsOk = bullets != null && bullets.split("\n|\r\n").length >= 5;
        if (!bulletsOk) { score -= 15; issues.append("五点描述不足5条; "); }

        boolean descOk = description != null && description.length() >= 300;
        if (!descOk) { score -= 10; issues.append("描述长度不足; "); }

        boolean imagesOk = imageCount != null && imageCount >= 7;
        if (!imagesOk) { score -= 20; issues.append("图片不足7张; "); }

        boolean searchOk = searchTerms != null && !searchTerms.isBlank();
        if (!searchOk) { score -= 20; issues.append("后台搜索词为空; "); }

        boolean aplusOk = true; // A+ 需要 SP-API 检查
        boolean listingActive = !"SUPPRESSED".equalsIgnoreCase(status) && !"INACTIVE".equalsIgnoreCase(status);
        if (!listingActive) { score -= 20; issues.append("Listing状态异常(").append(status).append("); "); }

        String severity;
        if (score >= 90) severity = "OK";
        else if (score >= 60) severity = "WARNING";
        else severity = "CRITICAL";

        ListingHealth health = new ListingHealth();
        health.setShopId(shopId);
        health.setAsin(asin);
        health.setStatus(listingActive ? "ACTIVE" : (status != null ? status : "INACTIVE"));
        health.setTitleOk(titleOk);
        health.setBulletPointsOk(bulletsOk);
        health.setDescriptionOk(descOk);
        health.setAplusOk(aplusOk);
        health.setImagesOk(imagesOk);
        health.setSearchTermsOk(searchOk);
        health.setSuppressedReason(issues.length() > 0 ? issues.toString().trim().replaceAll("; $", "") : null);
        health.setHealthScore(score);
        health.setSeverity(severity);
        health.setCheckTime(LocalDateTime.now());

        // upsert
        LambdaQueryWrapper<ListingHealth> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingHealth::getShopId, shopId).eq(ListingHealth::getAsin, asin);
        ListingHealth exist = listingHealthMapper.selectOne(wrapper);
        if (exist != null) {
            health.setId(exist.getId());
            listingHealthMapper.updateById(health);
        } else {
            listingHealthMapper.insert(health);
        }
        log.info("Listing健康检查 shopId={} asin={} score={} severity={}", shopId, asin, score, severity);
        return health;
    }

    @Override
    public List<ListingHealth> listHealth(Long shopId, String severity) {
        LambdaQueryWrapper<ListingHealth> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingHealth::getShopId, shopId);
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(ListingHealth::getSeverity, severity);
        }
        wrapper.orderByAsc(ListingHealth::getHealthScore);
        return listingHealthMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> healthSummary(Long shopId) {
        List<ListingHealth> all = listHealth(shopId, null);
        long ok = all.stream().filter(h -> "OK".equals(h.getSeverity())).count();
        long warning = all.stream().filter(h -> "WARNING".equals(h.getSeverity())).count();
        long critical = all.stream().filter(h -> "CRITICAL".equals(h.getSeverity())).count();
        double avgScore = all.stream().mapToInt(ListingHealth::getHealthScore).average().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("ok", ok);
        result.put("warning", warning);
        result.put("critical", critical);
        result.put("avgScore", Math.round(avgScore * 10) / 10.0);
        result.put("healthRate", all.size() > 0 ? Math.round((double) ok / all.size() * 1000) / 10.0 : 0);
        // Top5 低分
        result.put("worstListings", all.stream().limit(5)
                .map(h -> Map.of("asin", h.getAsin(), "score", h.getHealthScore(), "severity", h.getSeverity(),
                        "reason", h.getSuppressedReason() != null ? h.getSuppressedReason() : ""))
                .collect(Collectors.toList()));
        return result;
    }

    // ==================== 变更日志 ====================

    @Override
    public void logChange(Long shopId, String asin, String sku, String fieldName, String oldValue,
                          String newValue, String source, String operator) {
        ListingChangeLog logEntry = new ListingChangeLog();
        logEntry.setShopId(shopId);
        logEntry.setAsin(asin);
        logEntry.setSku(sku);
        logEntry.setFieldName(fieldName);
        logEntry.setOldValue(oldValue);
        logEntry.setNewValue(newValue);
        logEntry.setChangeSource(source != null ? source : "MANUAL");
        logEntry.setOperator(operator);
        logEntry.setChangeTime(LocalDateTime.now());
        listingChangeLogMapper.insert(logEntry);
    }

    @Override
    public List<ListingChangeLog> listChangeLogs(Long shopId, String asin, String fieldName) {
        LambdaQueryWrapper<ListingChangeLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingChangeLog::getShopId, shopId);
        if (asin != null && !asin.isBlank()) wrapper.eq(ListingChangeLog::getAsin, asin);
        if (fieldName != null && !fieldName.isBlank()) wrapper.eq(ListingChangeLog::getFieldName, fieldName);
        wrapper.orderByDesc(ListingChangeLog::getChangeTime);
        return listingChangeLogMapper.selectList(wrapper);
    }

    // ==================== 关键词排名 ====================

    @Override
    public KeywordRanking saveRanking(KeywordRanking ranking) {
        if (ranking.getRankDate() == null) ranking.setRankDate(LocalDate.now());
        if (ranking.getOrganicRank() == null) ranking.setOrganicRank(0);
        if (ranking.getAdRank() == null) ranking.setAdRank(0);
        keywordRankingMapper.insert(ranking);
        return ranking;
    }

    @Override
    public List<KeywordRanking> listRankings(Long shopId, String asin, String keyword) {
        LambdaQueryWrapper<KeywordRanking> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordRanking::getShopId, shopId);
        if (asin != null && !asin.isBlank()) wrapper.eq(KeywordRanking::getAsin, asin);
        if (keyword != null && !keyword.isBlank()) wrapper.like(KeywordRanking::getKeyword, keyword);
        wrapper.orderByDesc(KeywordRanking::getRankDate);
        return keywordRankingMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> rankingTrend(Long shopId, String asin, String keyword, Integer days) {
        if (days == null || days <= 0) days = 30;
        LocalDate since = LocalDate.now().minusDays(days);
        LambdaQueryWrapper<KeywordRanking> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordRanking::getShopId, shopId)
               .eq(KeywordRanking::getAsin, asin)
               .ge(KeywordRanking::getRankDate, since)
               .orderByAsc(KeywordRanking::getRankDate);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.eq(KeywordRanking::getKeyword, keyword);
        }
        List<KeywordRanking> rankings = keywordRankingMapper.selectList(wrapper);

        // 按日期聚合所有关键词排名
        Map<String, List<Map<String, Object>>> byKeyword = new LinkedHashMap<>();
        for (KeywordRanking r : rankings) {
            byKeyword.computeIfAbsent(r.getKeyword(), k -> new ArrayList<>())
                    .add(Map.of("date", r.getRankDate().toString(),
                            "organicRank", r.getOrganicRank() != null ? r.getOrganicRank() : 0,
                            "adRank", r.getAdRank() != null ? r.getAdRank() : 0));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("asin", asin);
        result.put("days", days);
        result.put("keywords", byKeyword);
        return result;
    }

    // ==================== 竞品监控 ====================

    @Override
    public CompetitorMonitor saveCompetitor(CompetitorMonitor monitor) {
        if (monitor.getSnapshotDate() == null) monitor.setSnapshotDate(LocalDate.now());
        // 计算价格变动（与最近一次快照比）
        LambdaQueryWrapper<CompetitorMonitor> lastWrapper = new LambdaQueryWrapper<>();
        lastWrapper.eq(CompetitorMonitor::getShopId, monitor.getShopId())
                   .eq(CompetitorMonitor::getCompetitorAsin, monitor.getCompetitorAsin())
                   .orderByDesc(CompetitorMonitor::getSnapshotDate)
                   .last("LIMIT 1");
        CompetitorMonitor last = competitorMonitorMapper.selectOne(lastWrapper);
        if (last != null && last.getPrice() != null && monitor.getPrice() != null) {
            monitor.setPriceChange(monitor.getPrice().subtract(last.getPrice()));
        }
        if (last != null && last.getReviewRating() != null && monitor.getReviewRating() != null) {
            monitor.setRatingChange(monitor.getReviewRating().subtract(last.getReviewRating()));
        }
        competitorMonitorMapper.insert(monitor);
        return monitor;
    }

    @Override
    public List<CompetitorMonitor> listCompetitors(Long shopId, String competitorAsin) {
        // 返回每个竞品的最新快照
        LambdaQueryWrapper<CompetitorMonitor> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CompetitorMonitor::getShopId, shopId);
        if (competitorAsin != null && !competitorAsin.isBlank())
            wrapper.eq(CompetitorMonitor::getCompetitorAsin, competitorAsin);
        wrapper.orderByDesc(CompetitorMonitor::getSnapshotDate);

        List<CompetitorMonitor> all = competitorMonitorMapper.selectList(wrapper);
        // 去重保留最新
        return new ArrayList<>(all.stream()
                .collect(Collectors.toMap(CompetitorMonitor::getCompetitorAsin, c -> c, (a, b) -> a, LinkedHashMap::new))
                .values());
    }

    @Override
    public Map<String, Object> competitorComparison(Long shopId, String myAsin, String competitorAsin, Integer days) {
        if (days == null || days <= 0) days = 30;
        LocalDate since = LocalDate.now().minusDays(days);

        LambdaQueryWrapper<CompetitorMonitor> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CompetitorMonitor::getShopId, shopId)
               .eq(CompetitorMonitor::getCompetitorAsin, competitorAsin)
               .ge(CompetitorMonitor::getSnapshotDate, since)
               .orderByAsc(CompetitorMonitor::getSnapshotDate);
        List<CompetitorMonitor> history = competitorMonitorMapper.selectList(wrapper);

        List<Map<String, Object>> trendData = history.stream().map(h -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", h.getSnapshotDate().toString());
            point.put("price", h.getPrice());
            point.put("bsRank", h.getBsRank());
            point.put("reviewCount", h.getReviewCount());
            point.put("reviewRating", h.getReviewRating());
            point.put("inStock", h.getInStock());
            point.put("hasCoupon", h.getHasCoupon());
            point.put("hasDeal", h.getHasDeal());
            return point;
        }).collect(Collectors.toList());

        CompetitorMonitor latest = history.isEmpty() ? null : history.get(history.size() - 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("myAsin", myAsin);
        result.put("competitorAsin", competitorAsin);
        result.put("days", days);
        result.put("latest", latest);
        result.put("trendData", trendData);
        return result;
    }

    // ==================== Buy Box ====================

    @Override
    public BuyBox saveBuyBox(BuyBox buyBox) {
        if (buyBox.getSnapshotTime() == null) buyBox.setSnapshotTime(LocalDateTime.now());
        if (buyBox.getIsSelf() == null) buyBox.setIsSelf(false);
        if (buyBox.getOwnershipPct() == null) buyBox.setOwnershipPct(BigDecimal.ZERO);
        buyBoxMapper.insert(buyBox);
        return buyBox;
    }

    @Override
    public List<BuyBox> listBuyBox(Long shopId, String asin) {
        // 返回每个 ASIN 最新快照
        LambdaQueryWrapper<BuyBox> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BuyBox::getShopId, shopId);
        if (asin != null && !asin.isBlank()) wrapper.eq(BuyBox::getAsin, asin);
        wrapper.orderByDesc(BuyBox::getSnapshotTime);
        List<BuyBox> all = buyBoxMapper.selectList(wrapper);
        return new ArrayList<>(all.stream()
                .collect(Collectors.toMap(BuyBox::getAsin, b -> b, (a, b) -> a, LinkedHashMap::new))
                .values());
    }

    @Override
    public Map<String, Object> buyBoxSummary(Long shopId) {
        List<BuyBox> all = listBuyBox(shopId, null);
        long total = all.size();
        long selfOwned = all.stream().filter(b -> b.getIsSelf() != null && b.getIsSelf()).count();

        // 失去 BuyBox 的 ASIN
        List<Map<String, Object>> lostBuyBox = all.stream()
                .filter(b -> !(b.getIsSelf() != null && b.getIsSelf()))
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asin", b.getAsin());
                    m.put("buyboxPrice", b.getBuyboxPrice());
                    m.put("ourPrice", b.getOurPrice());
                    m.put("priceGap", b.getPriceGap());
                    m.put("sellerId", b.getSellerId());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalAsins", total);
        result.put("selfOwned", selfOwned);
        result.put("selfOwnedPct", total > 0
                ? new BigDecimal(selfOwned).multiply(new BigDecimal("100")).divide(new BigDecimal(total), 1, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        result.put("lostBuyBox", lostBuyBox);
        result.put("lostCount", lostBuyBox.size());
        return result;
    }
}
