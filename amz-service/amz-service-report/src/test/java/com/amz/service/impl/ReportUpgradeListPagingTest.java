package com.amz.service.impl;

import com.amz.exception.InvalidParamException;
import com.amz.mapper.BusinessOverviewMapper;
import com.amz.mapper.InventoryTurnoverMapper;
import com.amz.mapper.ProfitDetailMapper;
import com.amz.mapper.SalesDailyMapper;
import com.amz.model.BusinessOverview;
import com.amz.model.InventoryTurnover;
import com.amz.model.ProfitDetail;
import com.amz.model.SalesDaily;
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
 * /report/v2 四个列表端点的游标分页契约（2026-10-07，HANDOFF 计划 5 与其遗留项）。
 * <p>
 * 动因：profit/list、inventory-turnover/list、sales-daily/list、business-overview/list
 * 之前都是无界 selectList 全量返回，这些表随聚合逐日增长，迟早把整表灌进一次响应。
 * 统一改成 (report_date,id) 复合 keyset 分页 + 探测行判截断 + 非法/跨端点游标 fail-closed，
 * 与 /report/profit/snapshot/list 同一口径。这些行为只有钉测试才不会在下一次改动里悄悄退化。
 * <p>
 * 方向必须分别钉死：profit / turnover / business-overview 是**降序取上界翻页**
 * （{@code report_date < cursor}），sales-daily 是**升序取下界翻页**（{@code report_date > cursor}，
 * 因为趋势图从旧到新消费）。载荷格式共用同一个 decode，翻错方向不会报错、只会翻页结果不对。
 * 纯 Mockito，不依赖数据库——与 RealtimeProfitServiceImplTest 同一套脚手架。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("/report/v2 列表游标分页契约")
class ReportUpgradeListPagingTest {

    @Mock
    private ProfitDetailMapper profitDetailMapper;

    @Mock
    private InventoryTurnoverMapper inventoryTurnoverMapper;

    @Mock
    private SalesDailyMapper salesDailyMapper;

    @Mock
    private BusinessOverviewMapper businessOverviewMapper;

    @InjectMocks
    private ReportUpgradeServiceImpl service;

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * "can not find lambda cache for this entity"。这里手动注册四个实体元信息，
     * 分页断言才能检查真实拼出来的条件（而不是只看 mapper 有没有被调用）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProfitDetail.class);
        TableInfoHelper.initTableInfo(assistant, InventoryTurnover.class);
        TableInfoHelper.initTableInfo(assistant, SalesDaily.class);
        TableInfoHelper.initTableInfo(assistant, BusinessOverview.class);
    }

    // ---------------- profit（降序，主用例） ----------------

    @Test
    @DisplayName("利润明细：未取满一页时 truncated=false、没有 nextCursor、total 保持未知")
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
    @DisplayName("利润明细：命中超过一页时 truncated=true，游标为 (report_date,id) 复合键，探测行不泄漏")
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
    @DisplayName("利润明细：携带 cursor 时按 (report_date,id) 复合**下界**过滤，且始终带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listProfitWithCursorUsesKeyset() {
        when(profitDetailMapper.selectList(any()))
                .thenReturn(List.of(profit(5L, "2026-09-20")));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor("2026-09-21|100"));

        service.listProfitDetails(1L, null, null, null, req);

        LambdaQueryWrapper<ProfitDetail> wrapper = captureProfit();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "列表查询必须带显式 LIMIT，不允许无界 selectList");
        assertTrue(segment.contains("report_date <"), "降序列表必须取上界方向 report_date <：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(100L),
                "游标里的 id 必须参与下界比较，实际参数表=" + wrapper.getParamNameValuePairs());
    }

    // ---------------- turnover（降序）与 salesDaily（升序）：方向分别钉死 ----------------

    @Test
    @DisplayName("库存周转：降序游标取上界方向（report_date <），与利润明细一致")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listTurnoverUsesDescendingKeyset() {
        when(inventoryTurnoverMapper.selectList(any()))
                .thenReturn(List.of(turnover(9L, "2026-09-19")));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor("2026-09-20|100"));

        service.listInventoryTurnover(1L, null, req);

        ArgumentCaptor<LambdaQueryWrapper<InventoryTurnover>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(inventoryTurnoverMapper).selectList(captor.capture());
        LambdaQueryWrapper<InventoryTurnover> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("report_date <"), "周转列表降序必须取 report_date < 上界：" + segment);
        assertTrue(segment.contains("LIMIT"), "周转列表必须带显式 LIMIT");
    }

    @Test
    @DisplayName("日销明细：升序游标取下界方向（report_date >），与降序列表相反")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listSalesDailyUsesAscendingKeyset() {
        when(salesDailyMapper.selectList(any()))
                .thenReturn(List.of(sales(9L, "2026-09-21")));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor("2026-09-20|100"));

        service.listSalesDaily(1L, null, null, null, req);

        ArgumentCaptor<LambdaQueryWrapper<SalesDaily>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(salesDailyMapper).selectList(captor.capture());
        LambdaQueryWrapper<SalesDaily> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        // 翻错方向（升序误用降序的 <）不会抛错，只会让「下一页」退回已经读过的日期——必须靠断言区分。
        assertTrue(segment.contains("report_date >"), "日销列表升序必须取 report_date > 下界：" + segment);
        assertFalse(segment.contains("report_date <"), "日销列表绝不能出现降序的 report_date < 条件：" + segment);
        assertTrue(segment.contains("LIMIT"), "日销列表必须带显式 LIMIT");
    }

    @Test
    @DisplayName("经营概览：降序游标取上界方向（report_date <），且带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listBusinessOverviewUsesDescendingKeyset() {
        when(businessOverviewMapper.selectList(any()))
                .thenReturn(List.of(overview(9L, "2026-09-19")));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor("2026-09-20|100"));

        service.listBusinessOverview(1L, null, null, req);

        ArgumentCaptor<LambdaQueryWrapper<BusinessOverview>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(businessOverviewMapper).selectList(captor.capture());
        LambdaQueryWrapper<BusinessOverview> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("report_date <"), "概览列表降序必须取 report_date < 上界：" + segment);
        assertTrue(segment.contains("LIMIT"), "概览列表必须带显式 LIMIT");
    }

    // ---------------- 游标解析的 fail-closed（共用 decodeDateIdCursor，一组即可） ----------------

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
        // 静默接受才会把别人的游标当成「无边界」，等于从第一页重读。
        PageRequest foreign = PageRequest.of(10, PageRequest.encodeCursor("2026-09-27 10:00:00|123"));

        assertThrows(InvalidParamException.class,
                () -> service.listProfitDetails(1L, null, null, null, foreign));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<ProfitDetail> captureProfit() {
        ArgumentCaptor<LambdaQueryWrapper<ProfitDetail>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(profitDetailMapper).selectList(captor.capture());
        return captor.getValue();
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

    private static InventoryTurnover turnover(Long id, String reportDate) {
        InventoryTurnover t = new InventoryTurnover();
        t.setId(id);
        t.setShopId(1L);
        t.setAsin("B0TEST0001");
        t.setReportDate(LocalDate.parse(reportDate));
        return t;
    }

    private static SalesDaily sales(Long id, String reportDate) {
        SalesDaily s = new SalesDaily();
        s.setId(id);
        s.setShopId(1L);
        s.setReportDate(LocalDate.parse(reportDate));
        s.setNetUnits(5);
        s.setNetSales(new BigDecimal("100"));
        return s;
    }

    private static BusinessOverview overview(Long id, String reportDate) {
        BusinessOverview o = new BusinessOverview();
        o.setId(id);
        o.setShopId(1L);
        o.setReportDate(LocalDate.parse(reportDate));
        o.setTotalSales(new BigDecimal("100"));
        return o;
    }

    /** 还原游标载荷（去掉 v1: 版本前缀），用于断言复合游标内容。 */
    private static String cursorPayload(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        return raw.startsWith("v1:") ? raw.substring(3) : raw;
    }
}
