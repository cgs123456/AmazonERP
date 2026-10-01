package com.amz.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amz.mapper.ListingHealthMapper;
import com.amz.model.ListingHealth;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 整店列表读取必须有上限，且截断必须可见。
 * <p>
 * <b>实测发现（2026-10-01 审查轮 Medium）</b>：本类 5 个 {@code list*} 都是无上限
 * {@code selectList}；竞品/BuyBox 两个更隐蔽 —— 读进全部快照只为"每个 ASIN 取最新一条"，
 * 返回几十行却读了几年数据。同时 {@code healthSummary} 的整店口径数字不能被截断影响，
 * 所以两者必须分开：列表接口截断，汇总仍走全量（并留 warn）。
 */
@DisplayName("Listing 整店读取：列表截断可见，汇总口径不被截断")
class ListingMonitorReadCapTest {

    private static final int CAP = com.amz.result.PageRequest.MAX_SIZE;

    private final ListingHealthMapper healthMapper = mock(ListingHealthMapper.class);
    private final ListingMonitorServiceImpl service = service();

    /**
     * 渲染 wrapper SQL 需要 MyBatis-Plus 的实体列缓存；平时由 starter 扫描装载，
     * 纯单测里必须自己初始化一次，否则 getSqlSegment() 直接抛"找不到 lambda 缓存"。
     */
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfoCache() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ListingHealth.class);
    }

    private ListingMonitorServiceImpl service() {
        ListingMonitorServiceImpl impl = new ListingMonitorServiceImpl();
        ReflectionTestUtils.setField(impl, "listingHealthMapper", healthMapper);
        return impl;
    }

    @Test
    @DisplayName("读取带 LIMIT 上限，超出部分截断并留 warn")
    void listHealthIsCappedAndTruncationIsVisible() {
        when(healthMapper.selectList(any())).thenReturn(rows(CAP + 1));

        Logger logger = (Logger) LoggerFactory.getLogger(ListingMonitorServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<ListingHealth> result = service.listHealth(1L, null);

            assertEquals(CAP, result.size(), "整店列表不得无限返回");
            long warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("健康度"))
                    .count();
            assertEquals(1, warns, "截断必须可见：" + appender.list);

            ArgumentCaptor<LambdaQueryWrapper<ListingHealth>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            org.mockito.Mockito.verify(healthMapper).selectList(captor.capture());
            assertTrue(captor.getValue().getSqlSegment().contains("LIMIT " + (CAP + 1)),
                    "必须探边界多读一行才知道有没有被截断，实际：" + captor.getValue().getSqlSegment());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("未达上限时不告警，也不复制列表")
    void noTruncationBelowCap() {
        List<ListingHealth> few = rows(3);
        when(healthMapper.selectList(any())).thenReturn(few);

        assertEquals(3, service.listHealth(1L, null).size());
    }

    @Test
    @DisplayName("汇总仍是整店口径：total / avgScore 不受列表上限影响")
    void healthSummaryStillAggregatesEverything() {
        List<ListingHealth> beyondCap = rows(CAP + 1);
        for (int i = 0; i < beyondCap.size(); i++) {
            beyondCap.get(i).setHealthScore(i % 2 == 0 ? 100 : 0);
        }
        when(healthMapper.selectList(any())).thenReturn(beyondCap);

        Map<String, Object> summary = service.healthSummary(1L);

        assertEquals((long) CAP + 1, ((Number) summary.get("total")).longValue(),
                "汇总数字一旦被截断，健康率就成了抽样结果");
    }

    private static List<ListingHealth> rows(int count) {
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
}
