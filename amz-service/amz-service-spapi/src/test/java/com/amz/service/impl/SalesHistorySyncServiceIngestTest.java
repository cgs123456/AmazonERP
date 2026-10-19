package com.amz.service.impl;

import com.amz.client.dto.ReportInfo;
import com.amz.client.parse.SalesTrafficParser;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.model.SalesHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code amz_sales_history} 写入方的落库段测试。
 * <p>
 * <b>为什么只测这一段</b>：同步链路的 createReport/轮询/download 要真实 SP-API 凭据，
 * 本机没有（§S33.114 §3.1 的既有约束）。而「解析结果 → upsert」这段正是
 * 新增逻辑的所在，且可以完全离线验证。报表解析本身由
 * {@code SalesTrafficParserTest} 覆盖。
 * <p>
 * <b>这里守的不变量</b>：
 * <ul>
 *   <li>shopId 由调用方给定，绝不从行数据里取（防跨店写入）；</li>
 *   <li>null 金额按 0 落库，不写 NULL 进 DECIMAL 列；</li>
 *   <li>upsert 返回 0 时必须报警告——「一行没写进去」与「本来就是这些值」不同形；</li>
 *   <li>空结果不是错误，但要说明「窗口内可能确实没有销量」。</li>
 * </ul>
 */
@DisplayName("销量历史落库：店铺归属、金额兜底、写入失败可见")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesHistorySyncServiceIngestTest {

    @Mock
    private SalesHistoryMapper salesHistoryMapper;

    private SalesHistorySyncService service;

    @BeforeEach
    void setUp() {
        service = new SalesHistorySyncService();
        ReflectionTestUtils.setField(service, "salesHistoryMapper", salesHistoryMapper);
        ReflectionTestUtils.setField(service, "batchSize", 500);
    }

    @Test
    @DisplayName("shopId 由调用方给定，不信任行数据")
    void shopIdComesFromCallerNotRowData() {
        SalesHistorySyncService.SyncReport report = report();
        when(salesHistoryMapper.upsertBatch(anyList())).thenReturn(1);

        service.ingest(42L, List.of(row("SKU-1", "2026-09-01", 3, "30.00")), report);

        ArgumentCaptor<List<SalesHistory>> captor = castCaptor();
        verify(salesHistoryMapper).upsertBatch(captor.capture());
        assertEquals(42L, captor.getValue().get(0).getShopId(),
                "店铺必须来自调用方——从行数据取会把数据写进别家店");
        assertEquals(1, report.getWritten());
    }

    @Test
    @DisplayName("null 金额按 0 落库，不写 NULL 进 DECIMAL 列")
    void nullRevenueBecomesZero() {
        SalesHistorySyncService.SyncReport report = report();
        when(salesHistoryMapper.upsertBatch(anyList())).thenReturn(1);

        service.ingest(1L, List.of(row("SKU-1", "2026-09-01", 5, null)), report);

        ArgumentCaptor<List<SalesHistory>> captor = castCaptor();
        verify(salesHistoryMapper).upsertBatch(captor.capture());
        assertEquals(BigDecimal.ZERO, captor.getValue().get(0).getRevenue());
    }

    @Test
    @DisplayName("upsert 返回 0：必须报警告，不与「写入成功」同形")
    void zeroAffectedRowsWarns() {
        SalesHistorySyncService.SyncReport report = report();
        when(salesHistoryMapper.upsertBatch(anyList())).thenReturn(0);

        service.ingest(1L, List.of(row("SKU-1", "2026-09-01", 3, "30.00")), report);

        assertEquals(0, report.getWritten(), "一行没写进去就不能记成已写入");
        assertFalse(report.getWarnings().isEmpty(),
                "静默的 0 会让调用方以为数据已经在库里，补货建议据此出数");
        assertTrue(report.getWarnings().get(0).contains("未写入"));
    }

    @Test
    @DisplayName("空结果不是错误，但要说明窗口内可能确实没有销量")
    void emptyRowsWarnsButDoesNotThrow() {
        SalesHistorySyncService.SyncReport report = report();

        service.ingest(1L, List.of(), report);

        assertEquals(0, report.getWritten());
        assertFalse(report.getWarnings().isEmpty());
        assertTrue(report.getWarnings().get(0).contains("可能确实没有销量"));
        verify(salesHistoryMapper, never()).upsertBatch(anyList());
    }

    @Test
    @DisplayName("大批量按 batchSize 分块，每块都要真的写进去")
    void largeBatchIsChunked() {
        ReflectionTestUtils.setField(service, "batchSize", 2);
        SalesHistorySyncService.SyncReport report = report();
        when(salesHistoryMapper.upsertBatch(anyList())).thenReturn(2);

        service.ingest(1L, List.of(
                row("SKU-1", "2026-09-01", 1, "10.00"),
                row("SKU-2", "2026-09-01", 2, "20.00"),
                row("SKU-3", "2026-09-01", 3, "30.00")), report);

        verify(salesHistoryMapper, org.mockito.Mockito.times(2)).upsertBatch(anyList());
        assertEquals(3, report.getWritten());
        assertTrue(report.getWarnings().isEmpty(), "两块都写成功时不应有警告");
    }

    @Test
    @DisplayName("shopId 为 null 直接拒绝——这是最可能把数据写进别家店的形状")
    void nullShopIdIsRejected() {
        SalesHistorySyncService.SyncReport report = report();

        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(null, List.of(row("SKU-1", "2026-09-01", 1, "1.00")), report));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<SalesHistory>> castCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private static SalesTrafficParser.SalesRow row(String sku, String date, int units, String revenue) {
        return new SalesTrafficParser.SalesRow(
                LocalDate.parse(date), sku, units,
                revenue == null ? null : new BigDecimal(revenue));
    }

    private static SalesHistorySyncService.SyncReport report() {
        return new SalesHistorySyncService.SyncReport(1L, "GET_SALES_AND_TRAFFIC_REPORT");
    }
}
