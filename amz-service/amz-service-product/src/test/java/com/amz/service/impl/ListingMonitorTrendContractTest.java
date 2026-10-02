package com.amz.service.impl;

import com.amz.mapper.BuyBoxMapper;
import com.amz.mapper.CompetitorMonitorMapper;
import com.amz.mapper.KeywordRankingMapper;
import com.amz.mapper.ListingChangeLogMapper;
import com.amz.mapper.ListingHealthMapper;
import com.amz.model.CompetitorMonitor;
import com.amz.model.KeywordRanking;
import com.amz.model.ListingHealth;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 趋势/对比/自查三个端点的返回契约。
 *
 * <p>三处都是「数字看起来正常，其实是被编出来的」这一类：
 * <ul>
 *   <li>排名没记录时补 0 —— 排名语义里 0 比 1 还好，图上就是一个永远在最上面的假点；</li>
 *   <li>竞品对比整段历史不带 LIMIT，且按时间升序取前 N 行 —— 命中上限时留下的是最老的快照，
 *       最新一次对比反而看不见；</li>
 *   <li>A+ 内容一律记成「正常」—— 实现里没有、也没人给它任何 A+ 信息，
 *       而这条记录会永久写进 {@code amz_listing_health} 并喂给健康度汇总。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Listing 趋势与自查的返回契约")
class ListingMonitorTrendContractTest {

    @Mock
    private ListingHealthMapper listingHealthMapper;
    @Mock
    private ListingChangeLogMapper listingChangeLogMapper;
    @Mock
    private KeywordRankingMapper keywordRankingMapper;
    @Mock
    private CompetitorMonitorMapper competitorMonitorMapper;
    @Mock
    private BuyBoxMapper buyBoxMapper;

    @InjectMocks
    private ListingMonitorServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KeywordRanking.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CompetitorMonitor.class);
    }

    /**
     * mapper 是被 mock 的，它不会执行 SQL，所以「有没有 LIMIT / 按什么排序」只能从
     * 真正发给数据库的那个 wrapper 上读出来——否则去掉 last(limitClause()) 的变异是抓不到的
     * （第一版就是这样：mock 给 600 行，服务照样截断，测试照样绿）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String sqlSentTo(com.baomidou.mybatisplus.core.mapper.BaseMapper<?> mapper) {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        return captor.getValue().getCustomSqlSegment();
    }

    private static KeywordRanking rank(String keyword, LocalDate date, Integer organic, Integer ad) {
        KeywordRanking r = new KeywordRanking();
        r.setShopId(1L);
        r.setAsin("B0TRENDFIX");
        r.setKeyword(keyword);
        r.setRankDate(date);
        r.setOrganicRank(organic);
        r.setAdRank(ad);
        return r;
    }

    private static CompetitorMonitor snap(LocalDate date, String price, Integer bsRank) {
        CompetitorMonitor c = new CompetitorMonitor();
        c.setShopId(1L);
        c.setCompetitorAsin("B0COMPETE01");
        c.setSnapshotDate(date);
        c.setPrice(price == null ? null : new BigDecimal(price));
        c.setBsRank(bsRank);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> points(Map<String, Object> trend, String keyword) {
        Map<String, List<Map<String, Object>>> byKeyword =
                (Map<String, List<Map<String, Object>>>) trend.get("keywords");
        return byKeyword.get(keyword);
    }

    @Test
    @DisplayName("排名缺失保持缺失：不能补成 0（0 在排名里比第一名还好）")
    void missingRanksStayMissing() {
        // SQL 是 rank_date 倒序，所以 mapper 交回来的是「新快照在前」；服务要反过来给升序
        when(keywordRankingMapper.selectList(any())).thenReturn(List.of(
                rank("yoga mat", LocalDate.parse("2026-09-03"), 7, null),
                rank("yoga mat", LocalDate.parse("2026-09-02"), null, 12)));

        Map<String, Object> trend = service.rankingTrend(1L, "B0TRENDFIX", "yoga mat", 30);

        List<Map<String, Object>> pts = points(trend, "yoga mat");
        assertEquals(2, pts.size());
        assertNull(pts.get(0).get("organicRank"), "第一天没有自然排名记录，必须还是 null");
        assertEquals(12, pts.get(0).get("adRank"));
        assertNull(pts.get(1).get("adRank"));
        assertFalse(pts.stream().anyMatch(p -> Integer.valueOf(0).equals(p.get("organicRank"))),
                "出现了被补出来的 0 名：" + pts);
    }

    @Test
    @DisplayName("趋势返回按日期升序，且取的是最近 N 个快照而不是最老的")
    void trendIsAscendingAndKeepsNewest() {
        List<KeywordRanking> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            // 倒序：i=0 是最新的快照，和 ORDER BY rank_date DESC 一致
            many.add(rank("k", LocalDate.parse("2026-01-01").plusDays(599 - i), 10 + (599 - i), null));
        }
        when(keywordRankingMapper.selectList(any())).thenReturn(many);

        Map<String, Object> trend = service.rankingTrend(1L, "B0TRENDFIX", null, 3650);

        List<Map<String, Object>> pts = points(trend, "k");
        assertEquals(Boolean.TRUE, trend.get("truncated"), "命中单读上限必须说出来");
        // 取回 600 行时 capRead 只留 500 行（SQL 倒序，所以留的是最近那一批），再反转成升序
        assertEquals(500, pts.size(), "实际 " + pts.size());
        String rankSql = sqlSentTo(keywordRankingMapper);
        assertTrue(rankSql.contains("LIMIT 501"), "趋势查询必须带探测上限：" + rankSql);
        assertFalse(rankSql.toUpperCase().contains("ASC"), "同时挂 ASC 与 DESC 时 MySQL 以 ASC 为准，截断丢的正是最新快照：");
        assertTrue(rankSql.contains("ORDER BY rank_date DESC"), "必须从最新的快照往前取：" + rankSql);
        assertEquals(many.get(499).getRankDate().toString(), pts.get(0).get("date"),
                "升序后第一个点应是窗口里最老的一天");
        assertEquals(many.get(0).getRankDate().toString(), pts.get(pts.size() - 1).get("date"),
                "最后一个点必须是最新的一天");
    }

    @Test
    @DisplayName("竞品对比：空历史时 latest 是 null，不抛异常也不编一个 0")
    void emptyCompetitorHistoryHasNullLatest() {
        when(competitorMonitorMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> cmp = service.competitorComparison(1L, "B0MINE00001", "B0COMPETE01", 30);

        assertNull(cmp.get("latest"));
        assertEquals(List.of(), cmp.get("trendData"));
        assertEquals(Boolean.FALSE, cmp.get("ownAsinCompared"),
                "myAsin 只是回显：没有查自己 Listing 的快照就不能假装在对比");
    }

    @Test
    @DisplayName("竞品对比取最近 N 个快照并按日期升序返回，latest 是最新那条")
    void competitorComparisonKeepsNewestSnapshots() {
        List<CompetitorMonitor> desc = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            desc.add(snap(LocalDate.parse("2026-09-30").minusDays(i), "19.99", 100 + i));
        }
        when(competitorMonitorMapper.selectList(any())).thenReturn(desc);

        Map<String, Object> cmp = service.competitorComparison(1L, "B0MINE00001", "B0COMPETE01", 3650);

        assertEquals(Boolean.TRUE, cmp.get("truncated"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) cmp.get("trendData");
        assertEquals(500, data.size(), "实际 " + data.size());
        String cmpSql = sqlSentTo(competitorMonitorMapper);
        assertTrue(cmpSql.contains("LIMIT 501"), "对比查询必须带探测上限：" + cmpSql);
        assertTrue(cmpSql.contains("ORDER BY snapshot_date DESC"), "必须从最新快照往前取：" + cmpSql);
        assertEquals(desc.get(499).getSnapshotDate().toString(), data.get(0).get("date"),
                "截断后留下的窗口里最老一天");
        assertEquals("2026-09-30", data.get(data.size() - 1).get("date"), "最后一条必须是最新快照");
        CompetitorMonitor latest = (CompetitorMonitor) cmp.get("latest");
        assertEquals(LocalDate.parse("2026-09-30"), latest.getSnapshotDate());
    }

    @Test
    @DisplayName("A+ 没给信息就是未知，并写进问题项；不再一律记成正常")
    void aplusIsNotFabricatedWhenNotProvided() {
        when(listingHealthMapper.selectOne(any())).thenReturn(null);

        ListingHealth h = service.checkListing(1L, "B0APLUS0001",
                null, null, null, null, null, null, null);

        assertNull(h.getAplusOk(), "调用方没提供 A+ 信息，页面也就不能说它正常");
        assertTrue(h.getSuppressedReason().contains("A+内容未检查"), h.getSuppressedReason());
        // 六项全没给 + 状态未知：分数按既有规则扣到底，不该因为 A+ 未知再多扣
        assertEquals(20, h.getHealthScore());
        assertEquals("CRITICAL", h.getSeverity());
        verify(listingHealthMapper).insert(h);
    }

    @Test
    @DisplayName("A+ 明确给了 true/false 时按给的值写，不覆盖成别的")
    void aplusHonoursProvidedAnswer() {
        when(listingHealthMapper.selectOne(any())).thenReturn(null);

        ListingHealth good = service.checkListing(1L, "B0APLUS0002",
                "x".repeat(90), "a\nb\nc\nd\ne", "y".repeat(320), 8, "terms", "ACTIVE", true);
        assertEquals(Boolean.TRUE, good.getAplusOk());
        assertEquals(100, good.getHealthScore());
        assertEquals("OK", good.getSeverity());
        assertFalse(good.getSuppressedReason() != null && good.getSuppressedReason().contains("A+内容未检查"),
                "给了答案就不该再报未检查");

        ListingHealth bad = service.checkListing(1L, "B0APLUS0003",
                "x".repeat(90), "a\nb\nc\nd\ne", "y".repeat(320), 8, "terms", "ACTIVE", false);
        assertEquals(Boolean.FALSE, bad.getAplusOk());
    }

    @Test
    @DisplayName("aplus_ok 必须能把 null 写下去：列默认值是 1，跳过 null 就等于擅自判成正常")
    void aplusColumnWritesNull() throws Exception {
        com.baomidou.mybatisplus.annotation.TableField ann =
                com.amz.model.ListingHealth.class.getDeclaredField("aplusOk")
                        .getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);
        org.junit.jupiter.api.Assertions.assertNotNull(ann,
                "aplusOk 少了 @TableField：默认策略会跳过 null，而列的 DEFAULT 1 会把它变成「已确认正常」");
        assertEquals(com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS, ann.insertStrategy());
        assertEquals(com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS, ann.updateStrategy());
    }

    @Test
    @DisplayName("同一 (shopId, asin) 已存在时更新而不是再插一行")
    void checkIsAnUpsertNotAnAppend() {
        ListingHealth exist = new ListingHealth();
        exist.setId(77L);
        exist.setShopId(1L);
        exist.setAsin("B0APLUS0004");
        when(listingHealthMapper.selectOne(any())).thenReturn(exist);

        ListingHealth h = service.checkListing(1L, "B0APLUS0004",
                null, null, null, null, null, null, null);

        assertEquals(77L, h.getId(), "复用的是既有行的主键，否则每次点都长出一行新记录");
        verify(listingHealthMapper).updateById(h);
        verify(listingHealthMapper, never()).insert(any(ListingHealth.class));
    }

    @Test
    @DisplayName("上限常量与页面文案同一口径：改常量不用去改两处说明")
    void capConstantIsSharedWithFrontendCopy() {
        when(keywordRankingMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> trend = service.rankingTrend(1L, "B0TRENDFIX", null, 30);
        assertEquals(Map.of(), trend.get("keywords"));
        assertEquals(Boolean.FALSE, trend.get("truncated"));
    }
}
