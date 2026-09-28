package com.amz.service.impl;

import com.amz.exception.InvalidParamException;
import com.amz.mapper.CostAllocationMapper;
import com.amz.mapper.ProfitDetailMapper;
import com.amz.mapper.ProfitSnapshotMapper;
import com.amz.model.CostAllocation;
import com.amz.model.ProfitSnapshot;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时利润服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归 B2：profitSummary 改走 SQL GROUP BY 后，响应结构（sku/asin/sales/netProfit/
 * margin/snapshotCount、netProfit 降序、 totals/overallMargin）必须与逐行版一致。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实时利润服务单元测试")
class RealtimeProfitServiceImplTest {

    @Mock
    private ProfitSnapshotMapper profitSnapshotMapper;

    @Mock
    private ProfitDetailMapper profitDetailMapper;

    @Mock
    private CostAllocationMapper costAllocationMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RealtimeProfitServiceImpl profitService;

    @Test
    @DisplayName("profitSummary：聚合分组正确求和并按 netProfit 降序")
    void profitSummaryAggregatesGroups() {
        when(profitSnapshotMapper.sumBySku(eq(1L), isNull(), isNull())).thenReturn(Arrays.asList(
                group("A", "ASIN-A", new BigDecimal("1000"), new BigDecimal("200"), 2L),
                group("B", "ASIN-B", new BigDecimal("500"), new BigDecimal("50"), 1L)));

        Map<String, Object> result = profitService.profitSummary(1L, null, null);

        assertEquals(new BigDecimal("1500"), result.get("totalSales"));
        assertEquals(new BigDecimal("250"), result.get("totalNetProfit"));
        assertEquals(new BigDecimal("16.67"), result.get("overallMargin"));
        assertEquals(2, result.get("skuCount"));
        List<?> summaries = (List<?>) result.get("skuSummaries");
        assertEquals(2, summaries.size());
        Map<?, ?> first = (Map<?, ?>) summaries.get(0);
        assertEquals("A", first.get("sku"));
        assertEquals("ASIN-A", first.get("asin"));
        assertEquals(new BigDecimal("20.00"), first.get("margin"));
    }

    @Test
    @DisplayName("profitSummary：空分组返回零值")
    void profitSummaryEmpty() {
        when(profitSnapshotMapper.sumBySku(eq(1L), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = profitService.profitSummary(1L, "2026-01-01 00:00:00",
                "2026-01-31 23:59:59");

        assertEquals(BigDecimal.ZERO, result.get("totalSales"));
        assertEquals(0, result.get("skuCount"));
    }

    // ---------------- 利润快照 / 分摊列表：游标分页与截断显式化 ----------------

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * “can not find lambda cache for this entity”。这里手动注册表元信息，
     * 分页断言才能检查真实拼出来的条件（而不是只看 mapper 有没有被调用）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProfitSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, CostAllocation.class);
    }

    @Test
    @DisplayName("listSnapshots：未取满一页时 truncated=false 且没有 nextCursor")
    void listSnapshotsNoTruncation() {
        when(profitSnapshotMapper.selectList(any()))
                .thenReturn(List.of(snapshot(2L, "2026-09-27 10:00:00")));

        PageResult<ProfitSnapshot> page =
                profitService.listSnapshots(1L, "SKU-1", null, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertNull(page.nextCursor());
        assertNull(page.total(), "不做 COUNT(*)，总数必须是「未知」而不是 0");
    }

    @Test
    @DisplayName("listSnapshots：命中超过一页时 truncated=true，游标为 (stat_time,id) 复合键，探测行不泄漏")
    void listSnapshotsTruncated() {
        List<ProfitSnapshot> probed = new ArrayList<>();
        probed.add(snapshot(30L, "2026-09-27 10:00:00"));
        probed.add(snapshot(29L, "2026-09-27 09:00:00"));
        probed.add(snapshot(28L, "2026-09-27 08:00:00"));
        when(profitSnapshotMapper.selectList(any())).thenReturn(probed);

        PageResult<ProfitSnapshot> page =
                profitService.listSnapshots(1L, "SKU-1", null, null, PageRequest.first(2));

        assertEquals(2, page.items().size(), "第 size+1 行只用于判定 hasMore，不能返回给调用方");
        assertTrue(page.truncated());
        assertEquals("2026-09-27 09:00:00|29", cursorPayload(page.nextCursor()),
                "游标必须是本页最后一行的 (stat_time,id) 复合键");
    }

    @Test
    @DisplayName("listSnapshots：携带 cursor 时按 (stat_time,id) 复合下界过滤，且始终带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listSnapshotsWithCursorUsesKeyset() {
        when(profitSnapshotMapper.selectList(any()))
                .thenReturn(List.of(snapshot(5L, "2026-09-27 07:00:00")));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor("2026-09-27 08:00:00|100"));

        profitService.listSnapshots(1L, "SKU-1", null, null, req);

        ArgumentCaptor<LambdaQueryWrapper<ProfitSnapshot>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(profitSnapshotMapper).selectList(captor.capture());
        LambdaQueryWrapper<ProfitSnapshot> wrapper = captor.getValue();
        // 必须先触发 getCustomSqlSegment()：MyBatis-Plus 参数表惰性填充，
        // 先取 paramNameValuePairs 会拿到空 Map，断言会假失败。
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "列表查询必须带显式 LIMIT，不允许无界 selectList");
        assertTrue(segment.contains("stat_time"), "复合游标必须把 stat_time 作为下界条件：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(100L),
                "游标里的 id 必须参与下界比较，实际参数表=" + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("listSnapshots：游标载荷格式非法必须报错，不能静默回退首页")
    void listSnapshotsRejectsBadCursor() {
        PageRequest bad = PageRequest.of(10, PageRequest.encodeCursor("garbage"));
        assertThrows(InvalidParamException.class,
                () -> profitService.listSnapshots(1L, "SKU-1", null, null, bad));
    }

    @Test
    @DisplayName("profitTrend：结果必须暴露 trendTruncated 与 snapshotCount，不能假装聚合完整")
    void profitTrendExposesTruncationFlag() {
        when(profitSnapshotMapper.selectList(any()))
                .thenReturn(List.of(snapshot(3L, "2026-09-27 10:00:00")));

        Map<String, Object> result = profitService.profitTrend(1L, "SKU-1", "ASIN-1", 24);

        assertEquals(24, result.get("hours"));
        assertEquals(1, result.get("snapshotCount"));
        assertEquals(Boolean.FALSE, result.get("trendTruncated"));
    }

    @Test
    @DisplayName("listAllocations：原无界 selectList 改为带 LIMIT 的分页")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listAllocationsPaged() {
        when(costAllocationMapper.selectList(any()))
                .thenReturn(List.of(allocation(1L, "2026-09-01")));

        PageResult<CostAllocation> page =
                profitService.listAllocations(1L, "HEADHAUL", null, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        ArgumentCaptor<LambdaQueryWrapper<CostAllocation>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(costAllocationMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("LIMIT"),
                "分摊列表此前完全没有上限，必须补上显式 LIMIT");
    }

    // ---------------- helpers ----------------

    private static ProfitSnapshot snapshot(Long id, String statTime) {
        ProfitSnapshot s = new ProfitSnapshot();
        s.setId(id);
        s.setShopId(1L);
        s.setSku("SKU-1");
        s.setStatTime(LocalDateTime.parse(statTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        s.setSalesAmount(new BigDecimal("100"));
        s.setNetProfit(new BigDecimal("10"));
        return s;
    }

    private static CostAllocation allocation(Long id, String allocDate) {
        CostAllocation a = new CostAllocation();
        a.setId(id);
        a.setShopId(1L);
        a.setCostType("HEADHAUL");
        a.setAllocDate(LocalDate.parse(allocDate));
        return a;
    }

    /** 还原游标载荷（去掉 v1: 版本前缀），用于断言复合游标内容。 */
    private static String cursorPayload(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        return raw.startsWith("v1:") ? raw.substring(3) : raw;
    }


    private static Map<String, Object> group(String sku, String asin, BigDecimal sales,
                                             BigDecimal netProfit, Long snapshotCount) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("sku", sku);
        g.put("asin", asin);
        g.put("sales", sales);
        g.put("netProfit", netProfit);
        g.put("snapshotCount", snapshotCount);
        return g;
    }
}
