package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.AdAsinKeywordMapper;
import com.amz.mapper.AdSearchTermMapper;
import com.amz.mapper.ConvertingTermMapper;
import com.amz.model.AdAsinKeyword;
import com.amz.model.AdSearchTerm;
import com.amz.model.ConvertingTerm;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.SearchTermService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 搜索词分析服务实现。
 *
 * <p>所有聚合型读取都使用 {@code (report_date, id)} keyset 分批扫描，单次 SQL
 * 不再拉取整张报表；出单词提取按“请求窗口快照”覆盖写入，重复执行不会把同一批
 * 搜索词订单再次累加。
 */
@Slf4j
@Service
public class SearchTermServiceImpl implements SearchTermService {

    private static final BigDecimal HIGH_ACOS = new BigDecimal("40");
    private static final BigDecimal LOW_CR = new BigDecimal("3");
    private static final long WASTE_IMPRESSION_THRESHOLD = 1000;

    /** 单次数据库扫描/写入块大小，与列表分页硬上限保持一致。 */
    private static final int BATCH_SIZE = PageRequest.MAX_SIZE;

    /** 分析结果中 Top 出单词/浪费词数量。 */
    private static final int TOP_LIMIT = 10;

    /** 聚类结果中 Top 词根数量。 */
    private static final int CLUSTER_LIMIT = 20;

    /** 避免 days 传入负数/过大值导致无界扫描。 */
    private static final int MAX_ANALYSIS_DAYS = 365;

    @Autowired
    private AdSearchTermMapper adSearchTermMapper;

    @Autowired
    private ConvertingTermMapper convertingTermMapper;

    @Autowired
    private AdAsinKeywordMapper adAsinKeywordMapper;

    @Override
    public AdSearchTerm saveSearchTerm(AdSearchTerm searchTerm) {
        if (searchTerm == null || searchTerm.getShopId() == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        requireShopAccess(searchTerm.getShopId());
        if (searchTerm.getReportDate() == null) {
            searchTerm.setReportDate(LocalDate.now());
        }
        // 计算派生指标
        calculateMetrics(searchTerm);
        adSearchTermMapper.insert(searchTerm);
        return searchTerm;
    }

    @Override
    public PageResult<AdSearchTerm> listSearchTerms(Long shopId, String campaignId, String searchTerm,
                                                     PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<AdSearchTerm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdSearchTerm::getShopId, shopId);
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq(AdSearchTerm::getCampaignId, campaignId);
        }
        if (searchTerm != null && !searchTerm.isBlank()) {
            wrapper.like(AdSearchTerm::getSearchTerm, searchTerm);
        }
        DateCursor cursor = parseDateCursor(req);
        if (cursor != null) {
            wrapper.and(w -> w.lt(AdSearchTerm::getReportDate, cursor.date())
                    .or(inner -> inner.eq(AdSearchTerm::getReportDate, cursor.date())
                            .lt(AdSearchTerm::getId, cursor.id())));
        }
        wrapper.orderByDesc(AdSearchTerm::getReportDate)
               .orderByDesc(AdSearchTerm::getId)
               .last("LIMIT " + req.probeSize());
        List<AdSearchTerm> rows = adSearchTermMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(),
                term -> PageRequest.encodeCursor(term.getReportDate() + "|" + term.getId()));
    }

    @Override
    public Map<String, Object> analyzeSearchTerms(Long shopId, String campaignId, Integer days) {
        int period = resolveDays(days);
        LocalDate startDate = LocalDate.now().minusDays(period);
        AnalyzeAccumulator accumulator = new AnalyzeAccumulator();

        scanSearchTerms(shopId, campaignId, startDate, null, page -> page.forEach(accumulator::accept));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("analysisPeriod", period);
        result.put("scannedRows", accumulator.totalTerms);
        result.put("scanBatchSize", BATCH_SIZE);
        result.put("truncated", false);
        result.put("totalSearchTerms", accumulator.totalTerms);
        result.put("convertingTerms", accumulator.convertingTerms);
        result.put("wasteTerms", accumulator.wasteTerms);
        result.put("highAcosTerms", accumulator.highAcosTerms);
        result.put("lowCrTerms", accumulator.lowCrTerms);
        result.put("totalCost", accumulator.totalCost);
        result.put("totalSales", accumulator.totalSales);
        result.put("wasteCost", accumulator.wasteCost);
        result.put("overallAcos", calculateAcos(accumulator.totalCost, accumulator.totalSales));
        result.put("topConvertingTerms", accumulator.topConvertingTerms());
        result.put("topWasteTerms", accumulator.topWasteTerms());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ConvertingTerm> extractConvertingTerms(Long shopId, Integer days) {
        int period = resolveDays(days);
        LocalDate startDate = LocalDate.now().minusDays(period);
        Map<ConvertingBusinessKey, ConvertingAggregate> aggregates = new LinkedHashMap<>();

        scanSearchTerms(shopId, null, startDate, 0, page -> {
            Map<ConvertingBusinessKey, List<AdSearchTerm>> grouped = page.stream()
                    .filter(Objects::nonNull)
                    .filter(t -> t.getSearchTerm() != null && !t.getSearchTerm().isBlank())
                    .collect(Collectors.groupingBy(
                            t -> new ConvertingBusinessKey(
                                    normalizeCampaignId(t.getCampaignId()),
                                    normalizeSearchTerm(t.getSearchTerm())),
                            LinkedHashMap::new,
                            Collectors.toList()));
            grouped.forEach((key, records) ->
                    aggregates.computeIfAbsent(key, ignored -> new ConvertingAggregate(key.campaignId(), key.searchTerm()))
                            .add(records));
        });

        List<ConvertingTerm> result = upsertConvertingTerms(shopId, aggregates);
        log.info("出单词提取完成：shopId={}, 窗口天数={}, 提取词数={}", shopId, period, result.size());
        return result;
    }

    @Override
    public PageResult<ConvertingTerm> listConvertingTerms(Long shopId, String asin, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<ConvertingTerm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConvertingTerm::getShopId, shopId)
               .eq(ConvertingTerm::getStatus, "ACTIVE");
        if (asin != null && !asin.isBlank()) {
            wrapper.eq(ConvertingTerm::getAsin, asin);
        }
        IntCursor cursor = parseIntCursor(req, "totalOrders");
        if (cursor != null) {
            wrapper.and(w -> w.lt(ConvertingTerm::getTotalOrders, cursor.key())
                    .or(inner -> inner.eq(ConvertingTerm::getTotalOrders, cursor.key())
                            .lt(ConvertingTerm::getId, cursor.id())));
        }
        wrapper.orderByDesc(ConvertingTerm::getTotalOrders)
               .orderByDesc(ConvertingTerm::getId)
               .last("LIMIT " + req.probeSize());
        List<ConvertingTerm> rows = convertingTermMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(),
                term -> PageRequest.encodeCursor(term.getTotalOrders() + "|" + term.getId()));
    }

    @Override
    public Map<String, Object> clusterSearchTerms(Long shopId, String campaignId, Integer days) {
        int period = resolveDays(days);
        LocalDate startDate = LocalDate.now().minusDays(period);
        Map<String, ClusterAccumulator> clusters = new HashMap<>();
        long[] scannedRows = {0};

        scanSearchTerms(shopId, campaignId, startDate, null, page -> {
            scannedRows[0] += page.size();
            for (AdSearchTerm term : page) {
                if (term == null) {
                    continue;
                }
                for (String root : extractRoots(term.getSearchTerm())) {
                    clusters.computeIfAbsent(root, ClusterAccumulator::new).add(term);
                }
            }
        });

        List<Map<String, Object>> clusterSummaries = clusters.values().stream()
                .map(ClusterAccumulator::toSummary)
                .sorted((a, b) -> Long.compare(
                        ((Number) b.get("totalOrders")).longValue(),
                        ((Number) a.get("totalOrders")).longValue()))
                .limit(CLUSTER_LIMIT)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("scannedRows", scannedRows[0]);
        result.put("scanBatchSize", BATCH_SIZE);
        result.put("truncated", false);
        result.put("totalClusters", clusters.size());
        result.put("topClusters", clusterSummaries);
        return result;
    }

    @Override
    public PageResult<AdAsinKeyword> reverseLookupAsin(Long shopId, String asin, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<AdAsinKeyword> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdAsinKeyword::getShopId, shopId)
               .eq(AdAsinKeyword::getAsin, asin);
        IntCursor cursor = parseIntCursor(req, "organicRank");
        if (cursor != null) {
            wrapper.and(w -> w.gt(AdAsinKeyword::getOrganicRank, cursor.key())
                    .or(inner -> inner.eq(AdAsinKeyword::getOrganicRank, cursor.key())
                            .gt(AdAsinKeyword::getId, cursor.id())));
        }
        wrapper.orderByAsc(AdAsinKeyword::getOrganicRank)
               .orderByAsc(AdAsinKeyword::getId)
               .last("LIMIT " + req.probeSize());
        List<AdAsinKeyword> rows = adAsinKeywordMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(),
                keyword -> PageRequest.encodeCursor(keyword.getOrganicRank() + "|" + keyword.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AdAsinKeyword> saveAsinKeywords(List<AdAsinKeyword> keywords) {
        if (keywords == null) {
            throw new AttrIsNullException("关键词列表不能为空");
        }
        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }

        // 先验证整批数据，避免前几项已写入后才发现越权项，造成部分提交。
        for (AdAsinKeyword kw : keywords) {
            if (kw == null || kw.getShopId() == null) {
                throw new AttrIsNullException("店铺ID不能为空");
            }
            if (kw.getAsin() == null || kw.getAsin().isBlank()) {
                throw new AttrIsNullException("ASIN 不能为空");
            }
            if (kw.getKeyword() == null || kw.getKeyword().isBlank()) {
                throw new AttrIsNullException("关键词不能为空");
            }
            requireShopAccess(kw.getShopId());
        }

        Map<String, AdAsinKeyword> deduplicated = new LinkedHashMap<>();
        for (AdAsinKeyword kw : keywords) {
            kw.setAsin(normalizeAsin(kw.getAsin()));
            kw.setKeyword(normalizeKeyword(kw.getKeyword()));
            if (kw.getLastChecked() == null) {
                kw.setLastChecked(LocalDate.now());
            }
            if (kw.getIsIndexed() == null) {
                kw.setIsIndexed(1);
            }
            // 同一批次内重复键以最后一条为准，避免同一请求内自我覆盖。
            deduplicated.put(asinKeywordKey(kw), kw);
        }

        List<AdAsinKeyword> persisted = new ArrayList<>(deduplicated.values());
        List<AdAsinKeyword> saved = new ArrayList<>(persisted.size());
        for (int from = 0; from < persisted.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, persisted.size());
            List<AdAsinKeyword> chunk = persisted.subList(from, to);
            saved.addAll(upsertAsinKeywordChunk(chunk));
        }
        return saved;
    }

    private List<AdAsinKeyword> upsertAsinKeywordChunk(List<AdAsinKeyword> chunk) {
        adAsinKeywordMapper.upsertBatch(chunk);

        Map<String, AdAsinKeyword> storedByKey = new HashMap<>();
        Map<Long, List<AdAsinKeyword>> byShop = chunk.stream()
                .collect(Collectors.groupingBy(AdAsinKeyword::getShopId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Long, List<AdAsinKeyword>> entry : byShop.entrySet()) {
            List<String> asins = entry.getValue().stream().map(AdAsinKeyword::getAsin).distinct().toList();
            List<String> keywords = entry.getValue().stream().map(AdAsinKeyword::getKeyword).distinct().toList();
            List<AdAsinKeyword> rows = adAsinKeywordMapper.selectByKeys(entry.getKey(), asins, keywords);
            if (rows != null) {
                for (AdAsinKeyword row : rows) {
                    if (row != null) {
                        storedByKey.put(asinKeywordKey(row), row);
                    }
                }
            }
        }

        List<AdAsinKeyword> result = new ArrayList<>(chunk.size());
        for (AdAsinKeyword intended : chunk) {
            AdAsinKeyword stored = storedByKey.get(asinKeywordKey(intended));
            result.add(stored == null ? intended : stored);
        }
        return result;
    }

    private List<ConvertingTerm> upsertConvertingTerms(Long shopId,
                                                        Map<ConvertingBusinessKey, ConvertingAggregate> aggregates) {
        List<ConvertingTerm> result = new ArrayList<>(aggregates.size());
        List<ConvertingAggregate> values = new ArrayList<>(aggregates.values());
        for (int from = 0; from < values.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, values.size());
            List<ConvertingAggregate> chunk = values.subList(from, to);
            List<ConvertingTerm> rows = chunk.stream()
                    .map(aggregate -> aggregate.toNewEntity(shopId))
                    .toList();
            List<String> campaignIds = rows.stream().map(ConvertingTerm::getCampaignId).distinct().toList();
            List<String> searchTerms = rows.stream().map(ConvertingTerm::getSearchTerm).distinct().toList();

            convertingTermMapper.upsertBatch(rows);
            List<ConvertingTerm> storedRows = convertingTermMapper.selectByKeys(shopId, campaignIds, searchTerms);
            Map<String, ConvertingTerm> storedByKey = new HashMap<>();
            if (storedRows != null) {
                for (ConvertingTerm stored : storedRows) {
                    if (stored != null) {
                        storedByKey.put(convertingTermKey(shopId, stored.getCampaignId(), stored.getSearchTerm()), stored);
                    }
                }
            }
            for (ConvertingTerm row : rows) {
                ConvertingTerm stored = storedByKey.get(
                        convertingTermKey(shopId, row.getCampaignId(), row.getSearchTerm()));
                result.add(stored == null ? row : stored);
            }
        }
        return result;
    }

    private void scanSearchTerms(Long shopId, String campaignId, LocalDate startDate, Integer minOrders,
                                 Consumer<List<AdSearchTerm>> consumer) {
        ScanCursor cursor = null;
        while (true) {
            LambdaQueryWrapper<AdSearchTerm> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AdSearchTerm::getShopId, shopId)
                   .ge(AdSearchTerm::getReportDate, startDate);
            if (campaignId != null && !campaignId.isBlank()) {
                wrapper.eq(AdSearchTerm::getCampaignId, campaignId);
            }
            if (minOrders != null) {
                wrapper.gt(AdSearchTerm::getOrders, minOrders);
            }
            if (cursor != null) {
                LocalDate date = cursor.date();
                long id = cursor.id();
                wrapper.and(w -> w.gt(AdSearchTerm::getReportDate, date)
                        .or(inner -> inner.eq(AdSearchTerm::getReportDate, date)
                                .gt(AdSearchTerm::getId, id)));
            }
            wrapper.orderByAsc(AdSearchTerm::getReportDate)
                   .orderByAsc(AdSearchTerm::getId)
                   .last("LIMIT " + BATCH_SIZE);

            List<AdSearchTerm> rows = adSearchTermMapper.selectList(wrapper);
            if (rows == null || rows.isEmpty()) {
                return;
            }
            consumer.accept(rows);
            if (rows.size() < BATCH_SIZE) {
                return;
            }
            AdSearchTerm last = rows.get(rows.size() - 1);
            if (last == null || last.getReportDate() == null || last.getId() == null) {
                throw new CodeErrorException("搜索词分页键缺失，无法继续扫描");
            }
            cursor = new ScanCursor(last.getReportDate(), last.getId());
        }
    }

    private static void requireShopAccess(Long shopId) {
        if (!UserContext.isShopAllowedStrict(shopId)) {
            throw new CodeErrorException("店铺不存在或无权访问");
        }
    }

    private static int resolveDays(Integer days) {
        int resolved = days == null ? 7 : days;
        if (resolved < 1 || resolved > MAX_ANALYSIS_DAYS) {
            throw new InvalidParamException("days 必须在 1-" + MAX_ANALYSIS_DAYS + " 之间，实际 " + resolved);
        }
        return resolved;
    }

    private static BigDecimal calculateAcos(BigDecimal cost, BigDecimal sales) {
        if (cost == null || sales == null || cost.compareTo(BigDecimal.ZERO) <= 0
                || sales.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return cost.multiply(new BigDecimal("100")).divide(sales, 2, RoundingMode.HALF_UP);
    }

    private static String normalizeAsin(String asin) {
        return asin == null ? "" : asin.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSearchTerm(String searchTerm) {
        return searchTerm == null ? "" : searchTerm.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeCampaignId(String campaignId) {
        return campaignId == null ? "" : campaignId.trim();
    }

    private static String asinKeywordKey(AdAsinKeyword keyword) {
        return keyword.getShopId() + "\0" + normalizeAsin(keyword.getAsin()) + "\0"
                + normalizeKeyword(keyword.getKeyword());
    }

    private static String convertingTermKey(Long shopId, String campaignId, String searchTerm) {
        return shopId + "\0" + normalizeCampaignId(campaignId) + "\0" + normalizeSearchTerm(searchTerm);
    }

    private static DateCursor parseDateCursor(PageRequest page) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("分页游标非法：reportDate 游标格式应为 yyyy-MM-dd|id");
        }
        try {
            LocalDate date = LocalDate.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new InvalidParamException("分页游标非法：id 必须 > 0，实际 " + id);
            }
            return new DateCursor(date, id);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new InvalidParamException("分页游标非法：reportDate 或 id 格式错误");
        }
    }

    private static IntCursor parseIntCursor(PageRequest page, String field) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("分页游标非法：" + field + " 游标格式应为 值|id");
        }
        try {
            int key = Integer.parseInt(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new InvalidParamException("分页游标非法：id 必须 > 0，实际 " + id);
            }
            return new IntCursor(key, id);
        } catch (NumberFormatException e) {
            throw new InvalidParamException("分页游标非法：" + field + " 或 id 不是整数");
        }
    }

    private record DateCursor(LocalDate date, long id) {
    }

    private record IntCursor(int key, long id) {
    }

    private record ScanCursor(LocalDate date, long id) {
    }

    private void calculateMetrics(AdSearchTerm term) {
        if (term.getImpressions() != null && term.getImpressions() > 0 && term.getClicks() != null) {
            term.setCtr(BigDecimal.valueOf(term.getClicks())
                    .multiply(new BigDecimal("100"))
                    .divide(BigDecimal.valueOf(term.getImpressions()), 2, RoundingMode.HALF_UP));
        }
        if (term.getClicks() != null && term.getClicks() > 0 && term.getCost() != null) {
            term.setCpc(term.getCost().divide(BigDecimal.valueOf(term.getClicks()), 2, RoundingMode.HALF_UP));
        }
        if (term.getClicks() != null && term.getClicks() > 0 && term.getOrders() != null) {
            term.setCr(BigDecimal.valueOf(term.getOrders())
                    .multiply(new BigDecimal("100"))
                    .divide(BigDecimal.valueOf(term.getClicks()), 2, RoundingMode.HALF_UP));
        }
        if (term.getSales() != null && term.getCost() != null && term.getSales().compareTo(BigDecimal.ZERO) > 0) {
            term.setAcos(term.getCost().multiply(new BigDecimal("100"))
                    .divide(term.getSales(), 2, RoundingMode.HALF_UP));
        }
    }

    /** 提取搜索词核心词根（简单实现：取长度>=3的英文单词） */
    private List<String> extractRoots(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return Collections.emptyList();
        }
        Pattern pattern = Pattern.compile("[a-zA-Z]{3,}");
        Matcher matcher = pattern.matcher(searchTerm.toLowerCase());
        List<String> roots = new ArrayList<>();
        while (matcher.find()) {
            roots.add(matcher.group());
        }
        return roots.isEmpty() ? Collections.singletonList(searchTerm) : roots;
    }

    private static int safeOrders(AdSearchTerm term) {
        return term.getOrders() == null ? 0 : term.getOrders();
    }

    private static BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class AnalyzeAccumulator {
        private long totalTerms;
        private long convertingTerms;
        private long wasteTerms;
        private long highAcosTerms;
        private long lowCrTerms;
        private BigDecimal totalCost = BigDecimal.ZERO;
        private BigDecimal totalSales = BigDecimal.ZERO;
        private BigDecimal wasteCost = BigDecimal.ZERO;
        private final PriorityQueue<AdSearchTerm> topConverting = new PriorityQueue<>(
                Comparator.comparingInt(SearchTermServiceImpl::safeOrders)
                        .thenComparing(t -> safeMoney(t.getSales())));
        private final PriorityQueue<AdSearchTerm> topWaste = new PriorityQueue<>(
                Comparator.comparing((AdSearchTerm t) -> safeMoney(t.getCost())));

        void accept(AdSearchTerm term) {
            if (term == null) {
                return;
            }
            totalTerms++;
            totalCost = totalCost.add(safeMoney(term.getCost()));
            totalSales = totalSales.add(safeMoney(term.getSales()));

            if (safeOrders(term) > 0) {
                convertingTerms++;
                if (term.getCr() != null && term.getCr().compareTo(LOW_CR) < 0) {
                    lowCrTerms++;
                }
                offer(topConverting, term, TOP_LIMIT);
            }
            if ((term.getClicks() == null || term.getClicks() == 0)
                    && term.getImpressions() != null && term.getImpressions() > WASTE_IMPRESSION_THRESHOLD) {
                wasteTerms++;
                wasteCost = wasteCost.add(safeMoney(term.getCost()));
            }
            if (term.getAcos() != null && term.getAcos().compareTo(HIGH_ACOS) >= 0) {
                highAcosTerms++;
            }
            if (safeOrders(term) == 0 && term.getCost() != null
                    && term.getCost().compareTo(BigDecimal.ZERO) > 0) {
                offer(topWaste, term, TOP_LIMIT);
            }
        }

        List<Map<String, Object>> topConvertingTerms() {
            return topConverting.stream()
                    .sorted(Comparator.comparingInt(SearchTermServiceImpl::safeOrders)
                            .thenComparing(t -> safeMoney(t.getSales()))
                            .reversed())
                    .map(term -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("searchTerm", term.getSearchTerm());
                        map.put("orders", term.getOrders());
                        map.put("sales", term.getSales());
                        map.put("cost", term.getCost());
                        map.put("acos", term.getAcos());
                        return map;
                    })
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> topWasteTerms() {
            return topWaste.stream()
                    .sorted(Comparator.comparing((AdSearchTerm t) -> safeMoney(t.getCost())).reversed())
                    .map(term -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("searchTerm", term.getSearchTerm());
                        map.put("cost", term.getCost());
                        map.put("impressions", term.getImpressions());
                        map.put("clicks", term.getClicks());
                        return map;
                    })
                    .collect(Collectors.toList());
        }

        private static <T> void offer(PriorityQueue<T> queue, T value, int limit) {
            queue.offer(value);
            if (queue.size() > limit) {
                queue.poll();
            }
        }
    }

    private static final class ClusterAccumulator {
        private final String root;
        private long termCount;
        private long totalImpressions;
        private long totalClicks;
        private long totalOrders;
        private BigDecimal totalCost = BigDecimal.ZERO;
        private BigDecimal totalSales = BigDecimal.ZERO;

        private ClusterAccumulator(String root) {
            this.root = root;
        }

        void add(AdSearchTerm term) {
            termCount++;
            totalImpressions += term.getImpressions() == null ? 0 : term.getImpressions();
            totalClicks += term.getClicks() == null ? 0 : term.getClicks();
            totalOrders += safeOrders(term);
            totalCost = totalCost.add(safeMoney(term.getCost()));
            totalSales = totalSales.add(safeMoney(term.getSales()));
        }

        Map<String, Object> toSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("root", root);
            summary.put("termCount", termCount);
            summary.put("totalImpressions", totalImpressions);
            summary.put("totalClicks", totalClicks);
            summary.put("totalCost", totalCost);
            summary.put("totalSales", totalSales);
            summary.put("totalOrders", totalOrders);
            return summary;
        }
    }

    private record ConvertingBusinessKey(String campaignId, String searchTerm) {
    }

    private static final class ConvertingAggregate {
        private final String campaignId;
        private final String searchTerm;
        private int totalOrders;
        private BigDecimal totalSales = BigDecimal.ZERO;
        private BigDecimal totalCost = BigDecimal.ZERO;
        private LocalDate firstSeen;
        private LocalDate lastSeen;

        private ConvertingAggregate(String campaignId, String searchTerm) {
            this.campaignId = campaignId;
            this.searchTerm = searchTerm;
        }

        void add(List<AdSearchTerm> records) {
            for (AdSearchTerm record : records) {
                if (record == null) {
                    continue;
                }
                totalOrders += safeOrders(record);
                totalSales = totalSales.add(safeMoney(record.getSales()));
                totalCost = totalCost.add(safeMoney(record.getCost()));
                if (record.getReportDate() != null) {
                    if (firstSeen == null || record.getReportDate().isBefore(firstSeen)) {
                        firstSeen = record.getReportDate();
                    }
                    if (lastSeen == null || record.getReportDate().isAfter(lastSeen)) {
                        lastSeen = record.getReportDate();
                    }
                }

            }
        }

        ConvertingTerm toNewEntity(Long shopId) {
            ConvertingTerm term = new ConvertingTerm();
            term.setShopId(shopId);
            term.setSearchTerm(searchTerm);
            term.setCampaignId(campaignId);
            term.setTotalOrders(totalOrders);
            term.setTotalSales(totalSales);
            term.setTotalCost(totalCost);
            term.setAvgAcos(calculateAcos(totalCost, totalSales));
            term.setFirstSeen(firstSeen);
            term.setLastSeen(lastSeen);
            term.setIsAddedToKeyword(0);
            term.setStatus("ACTIVE");
            return term;
        }

    }
}
