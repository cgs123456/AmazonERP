package com.amz.service.impl;

import com.amz.mapper.AdSearchTermMapper;
import com.amz.mapper.ConvertingTermMapper;
import com.amz.mapper.AdAsinKeywordMapper;
import com.amz.model.AdSearchTerm;
import com.amz.model.ConvertingTerm;
import com.amz.model.AdAsinKeyword;
import com.amz.service.SearchTermService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 搜索词分析服务实现。
 */
@Slf4j
@Service
public class SearchTermServiceImpl implements SearchTermService {

    private static final BigDecimal HIGH_ACOS = new BigDecimal("40");
    private static final BigDecimal LOW_CR = new BigDecimal("3");
    private static final long WASTE_IMPRESSION_THRESHOLD = 1000;

    @Autowired
    private AdSearchTermMapper adSearchTermMapper;

    @Autowired
    private ConvertingTermMapper convertingTermMapper;

    @Autowired
    private AdAsinKeywordMapper adAsinKeywordMapper;

    @Override
    public AdSearchTerm saveSearchTerm(AdSearchTerm searchTerm) {
        if (searchTerm.getReportDate() == null) {
            searchTerm.setReportDate(LocalDate.now());
        }
        // 计算派生指标
        calculateMetrics(searchTerm);
        adSearchTermMapper.insert(searchTerm);
        return searchTerm;
    }

    @Override
    public List<AdSearchTerm> listSearchTerms(Long shopId, String campaignId, String searchTerm) {
        LambdaQueryWrapper<AdSearchTerm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdSearchTerm::getShopId, shopId);
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq(AdSearchTerm::getCampaignId, campaignId);
        }
        if (searchTerm != null && !searchTerm.isBlank()) {
            wrapper.like(AdSearchTerm::getSearchTerm, searchTerm);
        }
        wrapper.orderByDesc(AdSearchTerm::getReportDate);
        return adSearchTermMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> analyzeSearchTerms(Long shopId, String campaignId, Integer days) {
        LocalDate startDate = LocalDate.now().minusDays(days != null ? days : 7);

        LambdaQueryWrapper<AdSearchTerm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdSearchTerm::getShopId, shopId)
               .ge(AdSearchTerm::getReportDate, startDate);
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq(AdSearchTerm::getCampaignId, campaignId);
        }
        List<AdSearchTerm> terms = adSearchTermMapper.selectList(wrapper);

        // 分类
        List<AdSearchTerm> convertingTerms = terms.stream()
                .filter(t -> t.getOrders() != null && t.getOrders() > 0)
                .collect(Collectors.toList());
        List<AdSearchTerm> wasteTerms = terms.stream()
                .filter(t -> (t.getClicks() == null || t.getClicks() == 0)
                        && (t.getImpressions() != null && t.getImpressions() > WASTE_IMPRESSION_THRESHOLD))
                .collect(Collectors.toList());
        List<AdSearchTerm> highAcosTerms = terms.stream()
                .filter(t -> t.getAcos() != null && t.getAcos().compareTo(HIGH_ACOS) >= 0)
                .collect(Collectors.toList());
        List<AdSearchTerm> lowCrTerms = convertingTerms.stream()
                .filter(t -> t.getCr() != null && t.getCr().compareTo(LOW_CR) < 0)
                .collect(Collectors.toList());

        // 汇总
        BigDecimal totalCost = terms.stream().map(AdSearchTerm::getCost).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSales = terms.stream().map(AdSearchTerm::getSales).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal wasteCost = wasteTerms.stream().map(AdSearchTerm::getCost).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("analysisPeriod", days != null ? days : 7);
        result.put("totalSearchTerms", terms.size());
        result.put("convertingTerms", convertingTerms.size());
        result.put("wasteTerms", wasteTerms.size());
        result.put("highAcosTerms", highAcosTerms.size());
        result.put("lowCrTerms", lowCrTerms.size());
        result.put("totalCost", totalCost);
        result.put("totalSales", totalSales);
        result.put("wasteCost", wasteCost);
        result.put("overallAcos", totalCost.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.multiply(new BigDecimal("100")).divide(totalSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);

        // Top 10 出单搜索词
        List<Map<String, Object>> topConverting = convertingTerms.stream()
                .sorted((a, b) -> (b.getOrders() != null ? b.getOrders() : 0) - (a.getOrders() != null ? a.getOrders() : 0))
                .limit(10)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("searchTerm", t.getSearchTerm());
                    m.put("orders", t.getOrders());
                    m.put("sales", t.getSales());
                    m.put("cost", t.getCost());
                    m.put("acos", t.getAcos());
                    return m;
                })
                .collect(Collectors.toList());
        result.put("topConvertingTerms", topConverting);

        // Top 10 浪费搜索词（有花费无转化）
        List<Map<String, Object>> topWaste = terms.stream()
                .filter(t -> (t.getOrders() == null || t.getOrders() == 0) && t.getCost() != null && t.getCost().compareTo(BigDecimal.ZERO) > 0)
                .sorted((a, b) -> b.getCost().compareTo(a.getCost()))
                .limit(10)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("searchTerm", t.getSearchTerm());
                    m.put("cost", t.getCost());
                    m.put("impressions", t.getImpressions());
                    m.put("clicks", t.getClicks());
                    return m;
                })
                .collect(Collectors.toList());
        result.put("topWasteTerms", topWaste);

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ConvertingTerm> extractConvertingTerms(Long shopId, Integer days) {
        LocalDate startDate = LocalDate.now().minusDays(days != null ? days : 7);

        LambdaQueryWrapper<AdSearchTerm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdSearchTerm::getShopId, shopId)
               .ge(AdSearchTerm::getReportDate, startDate)
               .gt(AdSearchTerm::getOrders, 0);
        List<AdSearchTerm> converting = adSearchTermMapper.selectList(wrapper);

        // 按 searchTerm 聚合
        Map<String, List<AdSearchTerm>> grouped = converting.stream()
                .collect(Collectors.groupingBy(AdSearchTerm::getSearchTerm));

        List<ConvertingTerm> result = new ArrayList<>();
        for (Map.Entry<String, List<AdSearchTerm>> entry : grouped.entrySet()) {
            String term = entry.getKey();
            List<AdSearchTerm> records = entry.getValue();

            int totalOrders = records.stream().mapToInt(t -> t.getOrders() != null ? t.getOrders() : 0).sum();
            BigDecimal totalSales = records.stream().map(AdSearchTerm::getSales).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCost = records.stream().map(AdSearchTerm::getCost).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgAcos = totalSales.compareTo(BigDecimal.ZERO) > 0
                    ? totalCost.multiply(new BigDecimal("100")).divide(totalSales, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            LocalDate firstSeen = records.stream().map(AdSearchTerm::getReportDate).min(LocalDate::compareTo).orElse(null);
            LocalDate lastSeen = records.stream().map(AdSearchTerm::getReportDate).max(LocalDate::compareTo).orElse(null);
            String asin = records.stream().map(AdSearchTerm::getCampaignId).findFirst().orElse(null);
            String campaignId = records.stream().map(AdSearchTerm::getCampaignId).findFirst().orElse(null);

            // Upsert 到出单词库
            LambdaQueryWrapper<ConvertingTerm> existWrapper = new LambdaQueryWrapper<>();
            existWrapper.eq(ConvertingTerm::getShopId, shopId)
                        .eq(ConvertingTerm::getSearchTerm, term);
            ConvertingTerm existing = convertingTermMapper.selectOne(existWrapper);

            if (existing != null) {
                existing.setTotalOrders(existing.getTotalOrders() + totalOrders);
                existing.setTotalSales(existing.getTotalSales().add(totalSales));
                existing.setTotalCost(existing.getTotalCost().add(totalCost));
                existing.setAvgAcos(existing.getTotalSales().compareTo(BigDecimal.ZERO) > 0
                        ? existing.getTotalCost().multiply(new BigDecimal("100")).divide(existing.getTotalSales(), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
                existing.setLastSeen(lastSeen);
                convertingTermMapper.updateById(existing);
                result.add(existing);
            } else {
                ConvertingTerm ct = new ConvertingTerm();
                ct.setShopId(shopId);
                ct.setSearchTerm(term);
                ct.setCampaignId(campaignId);
                ct.setTotalOrders(totalOrders);
                ct.setTotalSales(totalSales);
                ct.setTotalCost(totalCost);
                ct.setAvgAcos(avgAcos);
                ct.setFirstSeen(firstSeen);
                ct.setLastSeen(lastSeen);
                ct.setIsAddedToKeyword(0);
                ct.setStatus("ACTIVE");
                convertingTermMapper.insert(ct);
                result.add(ct);
            }
        }

        log.info("出单词提取完成：shopId={}, 提取词数={}", shopId, result.size());
        return result;
    }

    @Override
    public List<ConvertingTerm> listConvertingTerms(Long shopId, String asin) {
        LambdaQueryWrapper<ConvertingTerm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConvertingTerm::getShopId, shopId)
               .eq(ConvertingTerm::getStatus, "ACTIVE");
        if (asin != null && !asin.isBlank()) {
            wrapper.eq(ConvertingTerm::getAsin, asin);
        }
        wrapper.orderByDesc(ConvertingTerm::getTotalOrders);
        return convertingTermMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> clusterSearchTerms(Long shopId, String campaignId, Integer days) {
        LocalDate startDate = LocalDate.now().minusDays(days != null ? days : 7);

        LambdaQueryWrapper<AdSearchTerm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdSearchTerm::getShopId, shopId)
               .ge(AdSearchTerm::getReportDate, startDate);
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq(AdSearchTerm::getCampaignId, campaignId);
        }
        List<AdSearchTerm> terms = adSearchTermMapper.selectList(wrapper);

        // 按词根聚类（提取每个搜索词的核心词根）
        Map<String, List<AdSearchTerm>> clusters = new HashMap<>();
        for (AdSearchTerm term : terms) {
            List<String> roots = extractRoots(term.getSearchTerm());
            for (String root : roots) {
                clusters.computeIfAbsent(root, k -> new ArrayList<>()).add(term);
            }
        }

        // 汇总每个词根的指标
        List<Map<String, Object>> clusterSummaries = clusters.entrySet().stream()
                .map(e -> {
                    List<AdSearchTerm> group = e.getValue();
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("root", e.getKey());
                    summary.put("termCount", group.size());
                    summary.put("totalImpressions", group.stream().mapToLong(t -> t.getImpressions() != null ? t.getImpressions() : 0).sum());
                    summary.put("totalClicks", group.stream().mapToLong(t -> t.getClicks() != null ? t.getClicks() : 0).sum());
                    summary.put("totalCost", group.stream().map(AdSearchTerm::getCost).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
                    summary.put("totalSales", group.stream().map(AdSearchTerm::getSales).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
                    summary.put("totalOrders", group.stream().mapToInt(t -> t.getOrders() != null ? t.getOrders() : 0).sum());
                    return summary;
                })
                .sorted((a, b) -> Integer.compare((int) b.get("totalOrders"), (int) a.get("totalOrders")))
                .limit(20)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("totalClusters", clusters.size());
        result.put("topClusters", clusterSummaries);
        return result;
    }

    @Override
    public List<AdAsinKeyword> reverseLookupAsin(Long shopId, String asin) {
        LambdaQueryWrapper<AdAsinKeyword> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdAsinKeyword::getShopId, shopId)
               .eq(AdAsinKeyword::getAsin, asin)
               .orderByAsc(AdAsinKeyword::getOrganicRank);
        return adAsinKeywordMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AdAsinKeyword> saveAsinKeywords(List<AdAsinKeyword> keywords) {
        for (AdAsinKeyword kw : keywords) {
            if (kw.getLastChecked() == null) {
                kw.setLastChecked(LocalDate.now());
            }
            if (kw.getIsIndexed() == null) {
                kw.setIsIndexed(1);
            }
            // Upsert
            LambdaQueryWrapper<AdAsinKeyword> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AdAsinKeyword::getShopId, kw.getShopId())
                   .eq(AdAsinKeyword::getAsin, kw.getAsin())
                   .eq(AdAsinKeyword::getKeyword, kw.getKeyword());
            AdAsinKeyword existing = adAsinKeywordMapper.selectOne(wrapper);
            if (existing != null) {
                kw.setId(existing.getId());
                adAsinKeywordMapper.updateById(kw);
            } else {
                adAsinKeywordMapper.insert(kw);
            }
        }
        return keywords;
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
}
