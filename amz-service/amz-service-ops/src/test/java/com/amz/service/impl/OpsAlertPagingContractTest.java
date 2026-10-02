package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营预警三个读的端点的分页/上限契约。
 *
 * <p>差评告警与跟卖告警是被扫描持续写大的表，原来直接 {@code selectList} 不带 LIMIT：
 * HTTP 200 加一个数组，调用方看不出「整店读完了」还是「读了一部分」，一次请求也能把
 * 整表拉进内存。排名趋势不翻页（折线要的是连续序列），但必须给出点数上限，
 * 否则 mock 调度器跑得越久，一次趋势查询就越贵。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营预警列表分页与趋势上限")
class OpsAlertPagingContractTest {

    @Mock
    private NegativeReviewAlertMapper reviewAlertMapper;
    @Mock
    private HijackAlertMapper hijackAlertMapper;
    @Mock
    private KeywordRankRecordMapper rankMapper;

    @InjectMocks
    private OpsServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        for (Class<?> entity : List.of(NegativeReviewAlert.class, HijackAlert.class, KeywordRankRecord.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static LambdaQueryWrapper<?> captured(com.baomidou.mybatisplus.core.mapper.BaseMapper<?> mapper) {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static NegativeReviewAlert review(long id) {
        NegativeReviewAlert a = new NegativeReviewAlert();
        a.setId(id);
        a.setShopId(1L);
        a.setAsin("B0" + id);
        a.setStatus("NEW");
        return a;
    }

    private static HijackAlert hijack(long id) {
        HijackAlert a = new HijackAlert();
        a.setId(id);
        a.setShopId(1L);
        a.setAsin("B0" + id);
        a.setStatus("NEW");
        return a;
    }

    private static KeywordRankRecord rank(int position, String capturedAt) {
        KeywordRankRecord r = new KeywordRankRecord();
        r.setShopId(1L);
        r.setKeyword("wireless earbuds");
        r.setAsin("B0123456789");
        r.setRank(position);
        r.setMarketplace("US");
        r.setCaptureTime(capturedAt);
        return r;
    }

    @Test
    @DisplayName("差评告警按 id 倒序分页，探测 size+1 并给出游标")
    void reviewAlertsPagedById() {
        when(reviewAlertMapper.selectList(any()))
                .thenReturn(List.of(review(31L), review(30L), review(29L)));

        PageResult<NegativeReviewAlert> page =
                service.listNegativeReviewAlerts(1L, "NEW", PageRequest.of(2, null));

        assertEquals(List.of(31L, 30L), page.items().stream().map(NegativeReviewAlert::getId).toList());
        assertTrue(page.truncated(), "多探测出来一行就必须标记截断");
        assertEquals(PageRequest.encodeCursor(30L), page.nextCursor());
        String sql = captured(reviewAlertMapper).getCustomSqlSegment();
        assertTrue(sql.contains("LIMIT 3"), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
    }

    @Test
    @DisplayName("差评告警第二页带上游标：条件是 id < 游标，不是 OFFSET")
    void reviewAlertsUseKeysetCursor() {
        when(reviewAlertMapper.selectList(any())).thenReturn(List.of(review(28L)));

        PageResult<NegativeReviewAlert> page = service.listNegativeReviewAlerts(
                1L, null, PageRequest.of(2, PageRequest.encodeCursor(29L)));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertEquals(null, page.nextCursor());
        String sql = captured(reviewAlertMapper).getCustomSqlSegment();
        assertTrue(sql.contains("id <"), sql);
        assertTrue(!sql.toUpperCase().contains("OFFSET"), "keyset 分页不该退化成 OFFSET: " + sql);
    }

    @Test
    @DisplayName("状态筛选作为绑定参数下推，不拼进 SQL 文本")
    void reviewStatusFilterIsBoundNotInlined() {
        when(reviewAlertMapper.selectList(any())).thenReturn(List.of());

        service.listNegativeReviewAlerts(1L, "HANDLED", PageRequest.first(20));

        LambdaQueryWrapper<?> wrapper = captured(reviewAlertMapper);
        String sql = wrapper.getCustomSqlSegment();
        assertTrue(sql.contains("status = #{ew.paramNameValuePairs."), sql);
        assertTrue(!sql.contains("HANDLED"), "状态值被拼进 SQL 了：" + sql);
    }

    @Test
    @DisplayName("跟卖告警同样分页，且 shop_id 条件在，不按店铺读不到别人告警")
    void hijackAlertsPagedAndScopedToShop() {
        when(hijackAlertMapper.selectList(any()))
                .thenReturn(List.of(hijack(21L), hijack(20L), hijack(19L)));

        PageResult<HijackAlert> page = service.listHijackAlerts(1L, null, PageRequest.of(2, null));

        assertEquals(List.of(21L, 20L), page.items().stream().map(HijackAlert::getId).toList());
        assertTrue(page.hasMore());
        String sql = captured(hijackAlertMapper).getCustomSqlSegment();
        assertTrue(sql.contains("shop_id ="), sql);
        assertTrue(sql.contains("LIMIT 3"), sql);
    }

    @Test
    @DisplayName("排名趋势给最近 N 个点，返回顺序仍按抓取时间升序")
    void rankTrendIsCappedButStillAscending() {
        when(rankMapper.selectList(any())).thenReturn(List.of(
                rank(12, "2026-10-02 09:00:00"), rank(15, "2026-10-01 09:00:00")));

        List<KeywordRankRecord> trend = service.getRankTrend(1L, "wireless earbuds", "B0123456789");

        String sql = captured(rankMapper).getCustomSqlSegment();
        assertTrue(sql.toUpperCase().contains("LIMIT"), "趋势查询必须有上限: " + sql);
        assertTrue(sql.contains("LIMIT " + OpsServiceImpl.MAX_RANK_TREND_POINTS), sql);
        // 抓取时倒序取最近的点，返回给调用方要还原成时间升序，折线才不会左右反着画
        assertEquals(List.of("2026-10-01 09:00:00", "2026-10-02 09:00:00"),
                trend.stream().map(KeywordRankRecord::getCaptureTime).toList());
    }

    @Test
    @DisplayName("趋势上限是常量，不接受调用方传更大的值")
    void rankTrendCapIsBounded() {
        assertTrue(OpsServiceImpl.MAX_RANK_TREND_POINTS <= 500,
                "上限本身也要有边界：" + OpsServiceImpl.MAX_RANK_TREND_POINTS);
        assertTrue(OpsServiceImpl.MAX_RANK_TREND_POINTS >= 50,
                "上限太小会让趋势失去意义：" + OpsServiceImpl.MAX_RANK_TREND_POINTS);
    }
}
