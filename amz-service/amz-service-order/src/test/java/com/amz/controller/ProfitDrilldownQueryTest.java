package com.amz.controller;

import com.amz.mapper.ProfitReportMapper;
import com.amz.model.ProfitReport;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.result.PageMeta;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 利润下钻三查（按订单 / 按 SKU / 月度汇总）的查询契约。
 *
 * <p>动因：这三条端点在 {@code ProfitController} 里直连 mapper，返回的是「整表读进内存 +
 * HTTP 200 一个数组」——按 SKU 查会把该 SKU 从有数据以来每一天的行都吐出来，
 * 调用方看不出这是「全部」还是「一部分」；月度汇总按 SKU×月分组，行数随月份线性增长，
 * 也没有上限。同时页面上那个「按店铺」维度永远填不上（后端没有按店铺聚合利润的端点），
 * 所以在接 UI 之前先把这三条的形状定下来。
 *
 * <p>这个模块没有 service 层（控制器直接操作 mapper），所以分页形状在这里按控制器测；
 * 断言的是发给数据库的条件与返回的分页元数据，不是某句 SQL 的字面写法。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("利润下钻查询契约")
class ProfitDrilldownQueryTest {

    @Mock
    private ProfitReportMapper profitReportMapper;

    @InjectMocks
    private ProfitController controller;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProfitReport.class);
    }

    private static ProfitReport row(long id, String sku, String orderNo) {
        ProfitReport r = new ProfitReport();
        r.setId(id);
        r.setShopId(1L);
        r.setSku(sku);
        r.setAmazonOrderId(orderNo);
        r.setStatDate(LocalDate.parse("2026-06-1" + (id % 9)));
        r.setRevenue(new BigDecimal("100.00"));
        r.setNetProfit(new BigDecimal("20.00"));
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<?> capturedWrapper() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(profitReportMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("按订单查利润：探测 size+1 并给出分页元数据，不是裸数组")
    void orderByOrderIsPaged() {
        when(profitReportMapper.selectList(any())).thenReturn(List.of(
                row(41L, "SKU-A", "111-2222222-3333333"),
                row(40L, "SKU-B", "111-2222222-3333333"),
                row(39L, "SKU-C", "111-2222222-3333333")));

        Result<List<ProfitReport>> res = controller.getByOrder(1L, "111-2222222-3333333", 2, null);

        PageMeta meta = res.getPage();
        assertNotNull(meta, "分页元数据必须存在，调用方才能区分「读完了」和「只读了一页」");
        assertEquals(2, meta.getSize());
        assertEquals(2, meta.getReturned());
        assertTrue(meta.isTruncated());
        assertEquals(PageRequest.encodeCursor(40L), meta.getNextCursor());
        assertEquals(List.of(41L, 40L), res.getData().stream().map(ProfitReport::getId).toList());

        String sql = capturedWrapper().getCustomSqlSegment();
        assertTrue(sql.contains("LIMIT 3"), sql);
        assertTrue(sql.contains("amazon_order_id ="), sql);
        assertTrue(sql.contains("shop_id ="), sql);
    }

    @Test
    @DisplayName("按订单查第二页带上游标：条件是 id < 游标，不是 OFFSET")
    void orderDrilldownUsesKeysetCursor() {
        when(profitReportMapper.selectList(any())).thenReturn(List.of(row(38L, "SKU-D", "111-2222222-3333333")));

        Result<List<ProfitReport>> res = controller.getByOrder(1L, "111-2222222-3333333", 2, PageRequest.encodeCursor(39L));

        assertTrue(res.getPage().isHasMore() == false);
        String sql = capturedWrapper().getCustomSqlSegment();
        assertTrue(sql.contains("id <"), sql);
        assertTrue(!sql.toUpperCase().contains("OFFSET"), "keyset 分页不该退化成 OFFSET: " + sql);
    }

    @Test
    @DisplayName("坏游标要报错，不能被当成「没有游标」而从头再读一次")
    void badCursorIsRejected() {
        assertThrows(Exception.class, () -> controller.getBySku(1L, "SKU-A", 2, PageRequest.encodeCursor("not-a-number")));
    }

    @Test
    @DisplayName("按 SKU 查利润同样分页，并按记录 id 倒序（keyset 游标要求排序键唯一）")
    void skuDrilldownIsPaged() {
        when(profitReportMapper.selectList(any())).thenReturn(List.of(
                row(51L, "SKU-A", "O-1"), row(50L, "SKU-A", "O-2")));

        Result<List<ProfitReport>> res = controller.getBySku(1L, "SKU-A", 20, null);

        assertEquals(2, res.getData().size());
        assertTrue(!res.getPage().isTruncated(), "没有多探测出一行时不该谎报还有下一页");
        String sql = capturedWrapper().getCustomSqlSegment();
        assertTrue(sql.contains("sku ="), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
        assertTrue(sql.contains("LIMIT 21"), sql);
    }

    @Test
    @DisplayName("月度汇总的行数由调用侧传入上限兜住，mapper 用绑定参数而不是拼字符串")
    void monthlySummaryIsBoundedByParameter() {
        when(profitReportMapper.selectMonthlySummary(eq(1L), anyInt()))
                .thenReturn(List.of(Map.of("sku", "SKU-A", "month", "2026-06")));

        Result<List<Map<String, Object>>> res = controller.summary(1L);

        assertEquals(1, res.getData().size());
        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(profitReportMapper).selectMonthlySummary(eq(1L), cap.capture());
        assertTrue(cap.getValue() > 0 && cap.getValue() <= ProfitController.MAX_SUMMARY_ROWS,
                "汇总上限必须是个有限的正数，实际传入 " + cap.getValue());
        assertEquals(ProfitController.MAX_SUMMARY_ROWS, cap.getValue());
    }

    @Test
    @DisplayName("汇总 SQL 用 LIMIT 绑定参数并按月份倒序，未来改 SQL 不能把它写没了")
    void summarySqlKeepsBoundAndOrdering() throws Exception {
        Method m = ProfitReportMapper.class.getMethod("selectMonthlySummary", Long.class, int.class);
        String sql = String.join(" ", m.getAnnotation(Select.class).value());
        assertTrue(sql.contains("LIMIT #{maxRows}"), sql);
        assertTrue(sql.toUpperCase().contains("ORDER BY MONTH DESC"), sql);
        assertTrue(sql.contains("GROUP BY"), sql);
    }

    @Test
    @DisplayName("分页参数缺省时按默认页长，不是一次读完整表")
    void nullPageFallsBackToDefaultSize() {
        when(profitReportMapper.selectList(any())).thenReturn(List.of());

        controller.getBySku(1L, "SKU-A", null, null);

        String sql = capturedWrapper().getCustomSqlSegment();
        assertTrue(sql.contains("LIMIT " + (PageRequest.DEFAULT_SIZE + 1)), sql);
    }

    @Test
    @DisplayName("PageResult 的游标取的是本页最后一行的 id，调用方拿它就能续上")
    void cursorPointsAtLastReturnedRow() {
        PageResult<ProfitReport> page = PageResult.of(
                List.of(row(70L, "SKU-A", "O-1"), row(69L, "SKU-A", "O-2"), row(68L, "SKU-A", "O-3")),
                2, r -> PageRequest.encodeCursor(r.getId()));

        assertEquals(2, page.items().size());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(69L), page.nextCursor());
    }

    @Test
    @DisplayName("常量与包装类可用性自检：这里断言的是契约不是巧合")
    void contractHelpersAreWired() {
        assertNotNull(Constants.ENTITY);
        assertEquals(2, PageRequest.of(2, null).probeSize() - 1);
    }
}
