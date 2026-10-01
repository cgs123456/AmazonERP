package com.amz.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 整店列表读取必须有上限，且截断必须可见。
 * <p>
 * <b>实测发现</b>：本类 5 个 {@code list*} 原先都是无上限 {@code selectList}；
 * 竞品/BuyBox 两个更隐蔽 —— 读进全部快照只为"每个 ASIN 取最新一条"，返回几十行却读了几年数据。
 * 同时 {@code healthSummary} 的整店口径数字不能被截断影响，所以两者必须分开：
 * 列表接口截断，汇总仍走全量（并留 warn）。
 */
@DisplayName("Listing 整店读取：列表截断可见，汇总口径不被截断")
class ListingMonitorReadCapTest {

    private static final int CAP = PageRequest.MAX_SIZE;

    private final ListingHealthMapper healthMapper = mock(ListingHealthMapper.class);
    private final ListingChangeLogMapper changeLogMapper = mock(ListingChangeLogMapper.class);
    private final KeywordRankingMapper rankingMapper = mock(KeywordRankingMapper.class);
    private final CompetitorMonitorMapper competitorMapper = mock(CompetitorMonitorMapper.class);
    private final BuyBoxMapper buyBoxMapper = mock(BuyBoxMapper.class);
    private final ListingMonitorServiceImpl service = service();

    /**
     * 渲染 wrapper SQL 需要 MyBatis-Plus 的实体列缓存；平时由 starter 扫描装载，
     * 纯单测里必须自己初始化一次，否则 getSqlSegment() 直接抛"找不到 lambda 缓存"。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entity : List.of(ListingHealth.class, ListingChangeLog.class,
                KeywordRanking.class, CompetitorMonitor.class, BuyBox.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private ListingMonitorServiceImpl service() {
        ListingMonitorServiceImpl impl = new ListingMonitorServiceImpl();
        ReflectionTestUtils.setField(impl, "listingHealthMapper", healthMapper);
        ReflectionTestUtils.setField(impl, "listingChangeLogMapper", changeLogMapper);
        ReflectionTestUtils.setField(impl, "keywordRankingMapper", rankingMapper);
        ReflectionTestUtils.setField(impl, "competitorMonitorMapper", competitorMapper);
        ReflectionTestUtils.setField(impl, "buyBoxMapper", buyBoxMapper);
        return impl;
    }

    @Test
    @DisplayName("读取带 LIMIT 上限，超出部分截断并留 warn")
    void listHealthIsCappedAndTruncationIsVisible() {
        when(healthMapper.selectList(any())).thenReturn(healthRows(CAP + 1));

        Logger logger = (Logger) LoggerFactory.getLogger(ListingMonitorServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<ListingHealth> result = service.listHealth(1L, null);

            assertEquals(CAP, result.size(), "整店列表不得无限返回");
            assertEquals(1, warnsFor(appender, "健康度"), "截断必须可见：" + appender.list);

            ArgumentCaptor<LambdaQueryWrapper<ListingHealth>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(healthMapper).selectList(captor.capture());
            assertTrue(captor.getValue().getSqlSegment().contains("LIMIT " + (CAP + 1)),
                    "必须探边界多读一行才知道有没有被截断，实际：" + captor.getValue().getSqlSegment());
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 其余 4 个入口逐个测，而不是"共用 capRead 就等于都生效"：
     * 真正会退化的是 {@code .last(limitClause())} 那一步 —— 少写一处，SQL 仍然是合法查询，
     * 内存里的 capRead 照样把返回截到 500 行，调用方看到的数字完全正常，
     * 但"读几年快照只为取每 ASIN 最新一条"这个原始缺陷就回来了。
     */
    @Test
    @DisplayName("4 个入口各自的 SQL 都带 LIMIT，截断都留 warn")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyListEntrySendsTheLimitToTheDatabase() {
        Logger logger = (Logger) LoggerFactory.getLogger(ListingMonitorServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEntryCapped(changeLogMapper, ListingMonitorReadCapTest::changeLogRows,
                    () -> service.listChangeLogs(1L, "B1", "price"), "Listing 变更日志", appender);
            assertEntryCapped(rankingMapper, ListingMonitorReadCapTest::rankingRows,
                    () -> service.listRankings(1L, "B1", "yoga"), "关键词排名", appender);
            assertEntryCapped(competitorMapper, ListingMonitorReadCapTest::competitorRows,
                    () -> service.listCompetitors(1L, null), "竞品快照", appender);
            assertEntryCapped(buyBoxMapper, ListingMonitorReadCapTest::buyBoxRows,
                    () -> service.listBuyBox(1L, null), "BuyBox 快照", appender);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertEntryCapped(BaseMapper mapper, IntFunction<List<?>> rowFactory,
                                   Supplier<List<?>> call, String label,
                                   ListAppender<ILoggingEvent> appender) {
        int appenderBefore = appender.list.size();
        // 按 wrapper 应答：带 LIMIT 的读只会取回上限+1 行，不带的模拟"整店全量"
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
            boolean sendsLimit = wrapper.getSqlSegment().contains("LIMIT ");
            return rowFactory.apply(sendsLimit ? CAP + 1 : CAP + 400);
        });

        List<?> result = call.get();

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("LIMIT " + (CAP + 1)),
                label + " 没把上限交给数据库，实际 SQL：" + captor.getValue().getSqlSegment());
        assertEquals(CAP, result.size(), label + " 返回条数应被截到上限");
        assertEquals(1, warnsFor(appender.list.subList(appenderBefore, appender.list.size()), label),
                label + " 截断必须可见");
    }

    @Test
    @DisplayName("未达上限：不告警，也不额外复制一份列表")
    void noTruncationBelowCap() {
        List<ListingHealth> few = healthRows(3);
        when(healthMapper.selectList(any())).thenReturn(few);

        Logger logger = (Logger) LoggerFactory.getLogger(ListingMonitorServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<ListingHealth> result = service.listHealth(1L, null);

            assertEquals(3, result.size());
            assertSame(few, result, "没截断就不该再复制一份：整店读取每天上百次，白拷贝 500 个引用");
            assertEquals(0, warnsFor(appender, "健康度"), "未达上限不该报截断：" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("汇总口径独立于列表上限：整店计数走聚合，不再整店读入")
    void summaryDoesNotDependOnTheListCap() {
        // 列表读仍然带 LIMIT 并会被截断；汇总则走 aggregateHealthSummary，
        // 两条路分开之后，"为了汇总不截断只能全量扫"这个代价就没有存在的理由了。
        when(healthMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
            assertTrue(wrapper.getSqlSegment().contains("LIMIT "),
                    "列表读必须把上限交给数据库，实际：" + wrapper.getSqlSegment());
            return healthRows(CAP + 1);
        });
        when(healthMapper.aggregateHealthSummary(1L)).thenReturn(summaryRow(600, 300));
        when(healthMapper.selectWorstListings(eq(1L), anyInt())).thenReturn(List.of());

        assertEquals(CAP, service.listHealth(1L, null).size(), "列表读仍受上限约束");

        Map<String, Object> summary = service.healthSummary(1L);
        assertEquals(600L, ((Number) summary.get("total")).longValue(),
                "汇总必须是整店 600 行，而不是被截断后的 500 行");
        assertEquals(50.0, ((Number) summary.get("healthRate")).doubleValue(), 0.01);
        verify(healthMapper, times(1)).selectList(any());
    }

    private static Map<String, Object> summaryRow(long total, long ok) {
        Map<String, Object> row = new HashMap<>();
        row.put("total", total);
        row.put("okCount", ok);
        row.put("warningCount", total - ok);
        row.put("criticalCount", 0L);
        row.put("scoreSum", BigDecimal.valueOf(total * 60L));
        row.put("scoreCount", total);
        return row;
    }

    private static List<ListingHealth> healthRows(int count) {
        List<ListingHealth> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ListingHealth h = new ListingHealth();
            h.setShopId(1L);
            h.setAsin("A" + i);
            h.setHealthScore(50);
            h.setSeverity("OK");
            rows.add(h);
        }
        return rows;
    }

    private static List<?> changeLogRows(int count) {
        List<ListingChangeLog> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ListingChangeLog row = new ListingChangeLog();
            row.setShopId(1L);
            row.setAsin("A" + i);
            rows.add(row);
        }
        return rows;
    }

    private static List<?> rankingRows(int count) {
        List<KeywordRanking> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            KeywordRanking row = new KeywordRanking();
            row.setShopId(1L);
            row.setAsin("A" + i);
            row.setKeyword("yoga mat");
            rows.add(row);
        }
        return rows;
    }

    private static List<?> competitorRows(int count) {
        List<CompetitorMonitor> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompetitorMonitor row = new CompetitorMonitor();
            row.setShopId(1L);
            row.setCompetitorAsin("C" + i);
            rows.add(row);
        }
        return rows;
    }

    private static List<?> buyBoxRows(int count) {
        List<BuyBox> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BuyBox row = new BuyBox();
            row.setShopId(1L);
            row.setAsin("B" + i);
            rows.add(row);
        }
        return rows;
    }

    private static long warnsFor(ListAppender<ILoggingEvent> appender, String label) {
        return warnsFor(appender.list, label);
    }

    private static long warnsFor(List<ILoggingEvent> events, String label) {
        return events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(label))
                .count();
    }
}
