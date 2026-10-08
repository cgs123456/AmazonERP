package com.amz.service.impl;

import com.amz.exception.InvalidParamException;
import com.amz.mapper.ProfitDetailMapper;
import com.amz.model.ProfitDetail;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 利润明细列表的游标分页契约（2026-10-07，HANDOFF 计划 5：全量读改 size/cursor）。
 * <p>
 * 动因：/report/v2/profit/list 之前无界 selectList 全量返回，明细随聚合逐日增长，
 * 迟早把整表灌进一次响应。分页必须与 /report/profit/snapshot/list 同一口径
 * （(report_date,id) 复合游标 + 探测行判截断 + 非法游标 fail-closed），
 * 这些行为只有钉测试才不会在下一次改动里悄悄退化。
 * 纯 Mockito，不依赖数据库——与 RealtimeProfitServiceImplTest 同一套脚手架。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("利润明细游标分页契约")
class ReportUpgradeProfitPagingTest {

    @Mock
    private ProfitDetailMapper profitDetailMapper;

    @InjectMocks
    private ReportUpgradeServiceImpl service;

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * "can not find lambda cache for this entity"。这里手动注册表元信息，
     * 分页断言才能检查真实拼出来的条件（而不是只看 mapper 有没有被调用）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProfitDetail.class);
    }

    @Test
    @DisplayName("未取满一页：truncated=false、没有 nextCursor、total 保持未知")
    void listProfitNoTruncation() {
        when(profitDetailMapper.selectList(any()))
                .thenReturn(List.of(profit(2L, "2026-09-27")));

        PageResult<ProfitDetail> page =
                service.listProfitDetails(1L, "SKU-1", null, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertNull(page.nextCursor());
        assertNull(page.total(), "不做 COUNT(*)，总数必须是「未知」而不是 0");
    }

    @Test
    @DisplayName("命中超过一页：truncated=true，游标为 (report_date,id) 复合键，探测行不泄漏")
    void listProfitTruncated() {
        List<ProfitDetail> probed = new ArrayList<>();
        probed.add(profit(30L, "2026-09-27"));
        probed.add(profit(29L, "2026-09-26"));
        probed.add(profit(28L, "2026-09-25"));
        when(profitDetailMapper.selectList(any())).thenReturn(probed);

        PageResult<ProfitDetail> page =
                service.listProfitDetails(1L, null, null, null, PageRequest.first(2));

        assertEquals(2, page.items().size(), "第 size+1 行只用于判定 hasMore，不能返回给调用方");
        assertTrue(page.truncated());
        assertEquals("2026-09-26|29", cursorPayload(page.nextCursor()),
                "游标必须是本页最后一行的 (report_date,id) 复合键");
    }

    @Test
    @DisplayName("携带 cursor 时按 (report_date,id) 复合下界过滤，且始终带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listProfitWithCursorUsesKeyset() {
        when(profitDetailMapper.selectList(any()))
                .thenReturn(List.of(profit(5L, "2026-09-20")));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor("2026-09-21|100"));

        service.listProfitDetails(1L, null, null, null, req);

        ArgumentCaptor<LambdaQueryWrapper<ProfitDetail>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(profitDetailMapper).selectList(captor.capture());
        LambdaQueryWrapper<ProfitDetail> wrapper = captor.getValue();
        // 必须先触发 getCustomSqlSegment()：MyBatis-Plus 参数表惰性填充，
        // 先取 paramNameValuePairs 会拿到空 Map，断言会假失败。
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "列表查询必须带显式 LIMIT，不允许无界 selectList");
        assertTrue(segment.contains("report_date"), "复合游标必须把 report_date 作为下界条件：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(100L),
                "游标里的 id 必须参与下界比较，实际参数表=" + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("游标载荷格式非法必须报错，不能静默回退首页")
    void listProfitRejectsBadCursor() {
        PageRequest bad = PageRequest.of(10, PageRequest.encodeCursor("garbage"));

        assertThrows(InvalidParamException.class,
                () -> service.listProfitDetails(1L, null, null, null, bad));
    }

    @Test
    @DisplayName("游标跨端点混用（快照的 datetime 载荷）必须被日期解析拒绝")
    void listProfitRejectsForeignCursor() {
        // 快照游标是 "2026-09-27 10:00:00|123"：日期段带空格时间，LocalDate.parse 必炸——
        // 静默接受才会把别人的游标当成「无下界」，等于从第一页重读。
        PageRequest foreign = PageRequest.of(10, PageRequest.encodeCursor("2026-09-27 10:00:00|123"));

        assertThrows(InvalidParamException.class,
                () -> service.listProfitDetails(1L, null, null, null, foreign));
    }

    private static ProfitDetail profit(Long id, String reportDate) {
        ProfitDetail d = new ProfitDetail();
        d.setId(id);
        d.setShopId(1L);
        d.setAsin("B0TEST0001");
        d.setReportDate(LocalDate.parse(reportDate));
        d.setProductSales(new BigDecimal("100"));
        d.setNetProfit(new BigDecimal("10"));
        return d;
    }

    /** 还原游标载荷（去掉 v1: 版本前缀），用于断言复合游标内容。 */
    private static String cursorPayload(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        return raw.startsWith("v1:") ? raw.substring(3) : raw;
    }
}
