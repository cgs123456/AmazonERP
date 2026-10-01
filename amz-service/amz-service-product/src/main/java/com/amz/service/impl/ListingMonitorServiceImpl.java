package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.BuyBoxMapper;
import com.amz.mapper.CompetitorMonitorMapper;
import com.amz.mapper.KeywordRankingMapper;
import com.amz.mapper.ListingChangeLogMapper;
import com.amz.mapper.ListingHealthMapper;
import com.amz.model.BuyBox;
import com.amz.model.CompetitorMonitor;
import com.amz.model.KeywordRanking;
import com.amz.model.ListingChangeLog;
import com.amz.model.ListingHealth;
import com.amz.result.PageRequest;
import com.amz.service.ListingMonitorService;
import com.amz.util.MapArgUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Listing 健康监控服务实现。
 * <p>
 * 覆盖：健康度检查 / 变更日志 / 关键词排名追踪 / 竞品监控 / BuyBox 监控
 */
@Slf4j
@Service
public class ListingMonitorServiceImpl implements ListingMonitorService {

    /**
     * 整店列表的单读上限。
     * <p>
     * 存在动因：本类 5 个 {@code list*} 都是 {@code selectList} 无上限读取，行数随
     * 监控历史（每天每 ASIN 一条快照）线性累积；竞品/BuyBox 两个接口更隐蔽 ——
     * 它们把全部快照读进内存只为"每个 ASIN 取最新一条"，返回几十行却读了几年数据。
     */
    private static final int LIST_READ_LIMIT = PageRequest.MAX_SIZE;

    /** 健康度汇总里"最差 N 条"的展示条数（原先是整店读入后 {@code limit(5)}）。 */
    private static final int WORST_LIST_LIMIT = 5;

    /**
     * 按单读上限截断一次读取，并让截断这件事在日志里可见。
     * <p>
     * 各 {@code list*} 的 ORDER BY 都是"最相关在前"（健康分升序 / 时间降序），
     * 所以被截掉的是尾部旧快照；不静默丢弃 —— 静默会让调用方以为自己拿到了全量。
     * 需要更全的清单时应加过滤条件（asin / keyword / severity）或走分页接口。
     */
    private static <T> List<T> capRead(List<T> rows, String what) {
        if (rows == null || rows.size() <= LIST_READ_LIMIT) {
            return rows == null ? List.of() : rows;
        }
        log.warn("{} 整店读取命中单读上限 {}（实际取回 {} 行），尾部已截断；"
                + "需要更全的清单请加过滤条件或改用分页接口", what, LIST_READ_LIMIT, rows.size());
        return new ArrayList<>(rows.subList(0, LIST_READ_LIMIT));
    }

    /** 探边界多读一行：只有真读到上限+1 行才知道被截断了。 */
    private static String limitClause() {
        return "LIMIT " + (LIST_READ_LIMIT + 1);
    }

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
        return capRead(loadHealth(shopId, severity), "Listing 健康度");
    }

    /**
     * @param severity 可选过滤
     */
    private List<ListingHealth> loadHealth(Long shopId, String severity) {
        LambdaQueryWrapper<ListingHealth> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingHealth::getShopId, shopId);
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(ListingHealth::getSeverity, severity);
        }
        wrapper.orderByAsc(ListingHealth::getHealthScore).last(limitClause());
        return listingHealthMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> healthSummary(Long shopId) {
        // 汇总口径仍是整店，但不再为此把整店读进内存：计数与求和交给 SQL（见 mapper 注释，
        // 其中 severity 的大小写敏感比较是保持原口径的关键）。
        Map<String, Object> row = listingHealthMapper.aggregateHealthSummary(shopId);
        long total = longColumn(row, "total");
        long ok = longColumn(row, "okCount");
        long warning = longColumn(row, "warningCount");
        long critical = longColumn(row, "criticalCount");
        long scoreCount = longColumn(row, "scoreCount");
        BigDecimal scoreSum = decimalColumn(row, "scoreSum");
        // 换算留在 Java：与原先 IntStream.average() 的 double 除法逐步一致，
        // 换成 SQL 的 AVG 会拿 DECIMAL 标度，四舍五入边界上可能给出不同的展示值。
        double avgScore = scoreCount == 0 ? 0 : scoreSum.doubleValue() / scoreCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("ok", ok);
        result.put("warning", warning);
        result.put("critical", critical);
        result.put("avgScore", Math.round(avgScore * 10) / 10.0);
        result.put("healthRate", total > 0 ? Math.round((double) ok / total * 1000) / 10.0 : 0);
        result.put("worstListings", worstListings(shopId));
        return result;
    }

    /** 分数最低的 N 条 Listing；只读展示所需四列，不再整店读入后截五行。 */
    private List<Map<String, Object>> worstListings(Long shopId) {
        List<Map<String, Object>> rows = listingHealthMapper.selectWorstListings(shopId, WORST_LIST_LIMIT);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("asin", MapArgUtils.toStr(r, "asin"));
            item.put("score", MapArgUtils.toInt(r, "healthScore"));
            item.put("severity", MapArgUtils.toStr(r, "severity"));
            String reason = MapArgUtils.toStr(r, "suppressedReason");
            item.put("reason", reason != null ? reason : "");
            out.add(item);
        }
        return out;
    }

    /** 列缺失=契约坏了（改了别名或换了查询），必须吵；值为 NULL 才是空表的正常形态。 */
    private static long longColumn(Map<String, Object> row, String key) {
        if (row == null || !row.containsKey(key)) {
            throw new IllegalStateException("健康度聚合结果缺少列 " + key + "，实际列："
                    + (row == null ? null : row.keySet()));
        }
        Long value = MapArgUtils.toLong(row.get(key));
        return value == null ? 0L : value;
    }

    private static BigDecimal decimalColumn(Map<String, Object> row, String key) {
        if (row == null || !row.containsKey(key)) {
            throw new IllegalStateException("健康度聚合结果缺少列 " + key + "，实际列："
                    + (row == null ? null : row.keySet()));
        }
        BigDecimal value = MapArgUtils.toBigDecimal(row.get(key));
        return value == null ? BigDecimal.ZERO : value;
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
        wrapper.orderByDesc(ListingChangeLog::getChangeTime).last(limitClause());
        return capRead(listingChangeLogMapper.selectList(wrapper), "Listing 变更日志");
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
        wrapper.orderByDesc(KeywordRanking::getRankDate).last(limitClause());
        return capRead(keywordRankingMapper.selectList(wrapper), "关键词排名");
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
        wrapper.orderByDesc(CompetitorMonitor::getSnapshotDate).last(limitClause());

        // 截断发生在"每个竞品取最新快照"之前：命中上限时，可能整个竞品被漏掉而非只丢旧快照，
        // 因此这里必须留痕（capRead 内的 warn），而不是让调用方以为看到的是全部竞品。
        List<CompetitorMonitor> all = capRead(competitorMonitorMapper.selectList(wrapper), "竞品快照");
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
        wrapper.orderByDesc(BuyBox::getSnapshotTime).last(limitClause());
        // 同竞品：截断在"每个 ASIN 取最新快照"之前，命中上限会留 warn
        List<BuyBox> all = capRead(buyBoxMapper.selectList(wrapper), "BuyBox 快照");
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
