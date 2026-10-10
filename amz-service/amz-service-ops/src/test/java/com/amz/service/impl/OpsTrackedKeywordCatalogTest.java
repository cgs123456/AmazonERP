package com.amz.service.impl;

import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.model.KeywordRankRecord;
import com.amz.model.TrackedKeyword;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「本店被追踪的关键词」目录端点。
 *
 * <p>动因：{@code GET /ops/rank/trend} 要求 shopId+keyword+asin 三者都填，而全仓没有
 * 「列出本店追踪了哪些关键词」的端点——运营只能凭记忆输入<b>字面完全一致</b>的关键词，
 * 输错一个空格或大小写就是「没有记录」，界面上和「这个关键词真的没抓过」无法区分。
 * 关键词目录把可选项从表里读出来，让趋势页从「填空题」变成「选择题」。
 *
 * <p>口径（写死在断言里）：
 * <ul>
 *   <li>目录的唯一来源是 {@code amz_keyword_rank} 表本身：只有抓过的组合才存在，
 *       不引入「追踪清单」另一张表——那会多一个必须维护却无法验证的真相源；</li>
 *   <li>按 (keyword, asin) 去重，每组给出点数与最后一次抓取时刻；</li>
 *   <li>仍然走店铺归属：目录暴露了本店追踪了哪些竞品 ASIN，不能让别人列走。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("关键词排名：本店被追踪关键词目录")
class OpsTrackedKeywordCatalogTest {

    @Mock
    private NegativeReviewAlertMapper reviewAlertMapper;
    @Mock
    private HijackAlertMapper hijackAlertMapper;
    @Mock
    private KeywordRankRecordMapper rankMapper;

    @InjectMocks
    private OpsServiceImpl service;

    /**
     * MP 的 lambda 列解析依赖 TableInfo 缓存，纯 Mockito 下没有 Spring 上下文就没有这份缓存，
     * 一碰 {@code LambdaQueryWrapper} 就抛「can not find lambda cache」。
     * 这里手工注册（与 OpsAlertPagingContractTest 同法）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KeywordRankRecord.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String capturedSql() {
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(rankMapper).selectList(captor.capture());
        return captor.getValue().getCustomSqlSegment();
    }

    private static KeywordRankRecord rec(long id, String keyword, String asin, int rank, String captureTime) {
        KeywordRankRecord r = new KeywordRankRecord();
        r.setId(id);
        r.setShopId(1L);
        r.setKeyword(keyword);
        r.setAsin(asin);
        r.setRank(rank);
        r.setMarketplace("US");
        r.setCaptureTime(captureTime);
        return r;
    }

    @Test
    @DisplayName("目录按 (keyword, asin) 去重，同一组合的多个点并成一个条目")
    void catalogGroupsByKeywordAndAsin() {
        // 同一组合抓了 3 次 → 一个条目、3 个点；另一个组合 1 次
        when(rankMapper.selectList(any())).thenReturn(List.of(
                rec(3L, "wireless earbuds", "B01", 12, "2026-10-03 09:00:00"),
                rec(2L, "wireless earbuds", "B01", 40, "2026-10-02 09:00:00"),
                rec(1L, "wireless earbuds", "B01", 55, "2026-10-01 09:00:00"),
                rec(4L, "noise cancelling", "B01", 7, "2026-10-03 09:00:00")
        ));

        List<TrackedKeyword> catalog = service.listTrackedKeywords(1L);

        assertEquals(2, catalog.size());
        TrackedKeyword first = catalog.get(0);
        assertEquals("wireless earbuds", first.getKeyword());
        assertEquals("B01", first.getAsin());
        assertEquals(3, first.getPointCount());
        assertEquals("2026-10-03 09:00:00", first.getLastCaptureTime());
    }

    @Test
    @DisplayName("每组的最新排名取最后一次抓取的点，不是最大值也不是最小值")
    void latestRankComesFromLastCaptureNotExtreme() {
        when(rankMapper.selectList(any())).thenReturn(List.of(
                rec(3L, "wireless earbuds", "B01", 12, "2026-10-03 09:00:00"),
                rec(2L, "wireless earbuds", "B01", 40, "2026-10-02 09:00:00"),
                rec(1L, "wireless earbuds", "B01", 55, "2026-10-01 09:00:00")
        ));

        List<TrackedKeyword> catalog = service.listTrackedKeywords(1L);

        // 12 是最后一次（10-03）的值；40/55 是更早的点。取最值会得出 12 或 55 两种错答
        assertEquals(12, catalog.get(0).getLatestRank());
    }

    @Test
    @DisplayName("空表返回空目录，不抛异常，让页面能区分「没抓过」与「查询失败」")
    void emptyTableYieldsEmptyCatalog() {
        when(rankMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listTrackedKeywords(1L).isEmpty());
    }

    @Test
    @DisplayName("目录查询带上限：关键词是反复追加的，不能一次全表扫出来")
    void catalogIsCapped() {
        when(rankMapper.selectList(any())).thenReturn(List.of());

        service.listTrackedKeywords(1L);

        // 断言真的下了 LIMIT：没有上限时这个端点会随运行时间越来越贵
        String sql = capturedSql();
        assertTrue(sql.contains("LIMIT " + OpsServiceImpl.MAX_TRACKED_KEYWORD_SCAN),
                "目录查询必须带上限，实际 SQL 段：" + sql);
    }

    @Test
    @DisplayName("目录条目能直接喂给趋势查询，不再要求手输关键词")
    void catalogEntryFeedsTrendQuery() {
        when(rankMapper.selectList(any())).thenReturn(List.of(
                rec(1L, "wireless earbuds", "B01", 12, "2026-10-03 09:00:00")
        ));
        when(rankMapper.selectList(any())).thenReturn(List.of(
                rec(1L, "wireless earbuds", "B01", 12, "2026-10-03 09:00:00")
        ));

        TrackedKeyword k = service.listTrackedKeywords(1L).get(0);
        List<KeywordRankRecord> trend = service.getRankTrend(1L, k.getKeyword(), k.getAsin());

        assertEquals(1, trend.size());
        assertEquals(12, trend.get(0).getRank());
    }

    @Test
    @DisplayName("目录按店铺隔离：查询条件里必须带 shop_id，否则会列走别家追踪的竞品 ASIN")
    void catalogIsScopedToShop() {
        when(rankMapper.selectList(any())).thenReturn(List.of());

        service.listTrackedKeywords(1L);

        String sql = capturedSql();
        assertTrue(sql.contains("shop_id ="), "目录查询必须按 shop_id 过滤，实际 SQL 段：" + sql);
    }
}