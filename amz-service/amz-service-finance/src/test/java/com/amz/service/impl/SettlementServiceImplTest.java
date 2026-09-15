package com.amz.service.impl;

import com.amz.client.SpApiFinanceClient;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.dto.SettlementIngestReport;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.SettlementDetail;
import com.amz.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算原表接入服务单元测试（纯 Mockito）。
 * <p>
 * 重点验证三件事：
 * <ol>
 *   <li><b>幂等</b>：重复行被跳过而不是重复入账（结算报表跨窗口重叠拉取是常态）</li>
 *   <li><b>失败不静默</b>：报表 FATAL、spapi 降级、轮询超时都必须抛错，
 *       绝不能返回「0 条已导入」让上层读成本期没有结算</li>
 *   <li><b>报告口径</b>：读入行数 / 入库行数 / 跳过 / 行级错误 四个数各归各位</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceImplTest {

    private static final Long SHOP_ID = 1L;
    private static final String MARKETPLACE = "ATVPDKIKX0DER";

    private static final String HEADER = String.join("\t",
            "settlement-id", "settlement-start-date", "settlement-end-date", "deposit-date",
            "currency", "transaction-type", "order-id", "sku", "amount-type", "amount");

    @Mock
    private SpApiFinanceClient spApiFinanceClient;

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @InjectMocks
    private SettlementServiceImpl settlementService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(settlementService, "maxPollAttempts", 3);
        ReflectionTestUtils.setField(settlementService, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(settlementService, "batchSize", 200);
        ReflectionTestUtils.setField(settlementService, "maxRowErrors", 50);
    }

    private static String row(String currency, String txType, String orderId, String sku,
                              String amountType, String amount) {
        return String.join("\t", "900001", "2026-09-01T00:00:00Z", "2026-09-07T23:59:59Z",
                "2026-09-08T00:00:00Z", currency, txType, orderId, sku, amountType, amount);
    }

    private static String sampleTsv() {
        return HEADER + "\n"
                + row("USD", "Order", "111-0001", "SKU-ALPHA", "Principal", "29.99") + "\n"
                + row("USD", "Order", "111-0001", "SKU-ALPHA", "Commission", "-4.50") + "\n"
                + row("USD", "Order", "111-0001", "SKU-ALPHA", "FBAPerUnitFulfillmentFee", "-5.03") + "\n"
                + row("USD", "Refund", "111-0002", "SKU-BETA", "Principal", "-49.99") + "\n"
                + row("USD", "Adjustment", "", "SKU-GAMMA", "FBA Inventory Reimbursement", "12.50") + "\n";
    }

    private void stubHappyReportPath(String content) {
        when(spApiFinanceClient.requestReport(SHOP_ID, MARKETPLACE,
                SettlementServiceImpl.REPORT_TYPE_SETTLEMENT, null, null))
                .thenReturn(Result.success("RPT-1"));
        RemoteReportInfo done = new RemoteReportInfo();
        done.setReportId("RPT-1");
        done.setProcessingStatus("DONE");
        done.setDocumentId("DOC-1");
        when(spApiFinanceClient.getReport("RPT-1", SHOP_ID)).thenReturn(Result.success(done));
        when(spApiFinanceClient.downloadDocument("DOC-1", SHOP_ID)).thenReturn(Result.success(content));
    }

    @Test
    @DisplayName("正常同步：5 行全部入库，金额合计与币种正确")
    void syncHappyPath() {
        stubHappyReportPath(sampleTsv());
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(new ArrayList<>());
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenReturn(1);

        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(5, report.getDataLineCount());
        assertEquals(5, report.getInserted());
        assertEquals(0, report.getSkipped());
        assertEquals(0, report.getFailed());
        assertEquals(new BigDecimal("-17.03"), report.getSumAmount(),
                "29.99 - 4.50 - 5.03 - 49.99 + 12.50");
        assertEquals(1, report.getCurrencies().size());
        assertTrue(report.getCurrencies().contains("USD"));
        assertTrue(report.getWarnings().isEmpty(), "单币种正常批次不应有警告");
        assertEquals("RPT-1", report.getReportId());
        assertEquals("DONE", report.getReportStatus());
    }

    @Test
    @DisplayName("幂等：已存在的指纹行全部跳过，不重复入账")
    void duplicateRowsAreSkipped() {
        stubHappyReportPath(sampleTsv());
        // 库中已存在全部指纹 → 全部命中跳过
        when(settlementDetailMapper.selectExistingRowKeys(anyList()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));

        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(0, report.getInserted());
        assertEquals(5, report.getSkipped());
        assertEquals(BigDecimal.ZERO.setScale(2), report.getSumAmount(),
                "全部跳过时本批入库金额为 0（钱在上一批已计入）");
        verify(settlementDetailMapper, times(0)).insert(any(SettlementDetail.class));
    }

    @Test
    @DisplayName("批内去重：同一文件里出现完全相同的行时只入库一次")
    void withinBatchDuplicates() {
        String duplicated = HEADER + "\n"
                + row("USD", "Order", "111-0001", "SKU-ALPHA", "Principal", "29.99") + "\n"
                + row("USD", "Order", "111-0001", "SKU-ALPHA", "Principal", "29.99") + "\n";
        stubHappyReportPath(duplicated);
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(new ArrayList<>());
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenReturn(1);

        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(2, report.getDataLineCount());
        assertEquals(1, report.getInserted());
        assertEquals(1, report.getSkipped());
        verify(settlementDetailMapper, times(1)).insert(any(SettlementDetail.class));
    }

    @Test
    @DisplayName("报表处理失败（FATAL）：抛错而非返回空结果")
    void fatalReportThrows() {
        when(spApiFinanceClient.requestReport(eq(SHOP_ID), eq(MARKETPLACE), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-BAD"));
        RemoteReportInfo fatal = new RemoteReportInfo();
        fatal.setReportId("RPT-BAD");
        fatal.setProcessingStatus("FATAL");
        when(spApiFinanceClient.getReport("RPT-BAD", SHOP_ID)).thenReturn(Result.success(fatal));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> settlementService.sync(SHOP_ID, MARKETPLACE, null, null));
        assertTrue(ex.getMessage().contains("FATAL"), ex.getMessage());
    }

    @Test
    @DisplayName("spapi 降级（Feign fallback）：抛错并带上下游原因")
    void degradedFeignThrows() {
        when(spApiFinanceClient.requestReport(eq(SHOP_ID), eq(MARKETPLACE), anyString(), any(), any()))
                .thenReturn(Result.failure("spapi finance service degraded: connect timeout"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> settlementService.sync(SHOP_ID, MARKETPLACE, null, null));
        assertTrue(ex.getMessage().contains("创建结算报表请求失败"), ex.getMessage());
        assertTrue(ex.getMessage().contains("degraded"), ex.getMessage());
    }

    @Test
    @DisplayName("轮询：IN_PROGRESS 后转 DONE 应继续轮询并最终成功")
    void pollsUntilDone() {
        when(spApiFinanceClient.requestReport(eq(SHOP_ID), eq(MARKETPLACE), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-2"));
        RemoteReportInfo running = new RemoteReportInfo();
        running.setReportId("RPT-2");
        running.setProcessingStatus("IN_PROGRESS");
        RemoteReportInfo done = new RemoteReportInfo();
        done.setReportId("RPT-2");
        done.setProcessingStatus("DONE");
        done.setDocumentId("DOC-2");
        when(spApiFinanceClient.getReport("RPT-2", SHOP_ID))
                .thenReturn(Result.success(running), Result.success(done));
        when(spApiFinanceClient.downloadDocument("DOC-2", SHOP_ID))
                .thenReturn(Result.success(HEADER + "\n"
                        + row("USD", "Order", "111-1", "SKU-A", "Principal", "1.00") + "\n"));
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(new ArrayList<>());
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenReturn(1);

        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(1, report.getInserted());
        verify(spApiFinanceClient, times(2)).getReport("RPT-2", SHOP_ID);
    }

    @Test
    @DisplayName("轮询超时：抛错并说明已尝试次数（不静默返回空数据）")
    void pollTimeoutThrows() {
        when(spApiFinanceClient.requestReport(eq(SHOP_ID), eq(MARKETPLACE), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-3"));
        RemoteReportInfo running = new RemoteReportInfo();
        running.setReportId("RPT-3");
        running.setProcessingStatus("IN_QUEUE");
        when(spApiFinanceClient.getReport("RPT-3", SHOP_ID)).thenReturn(Result.success(running));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> settlementService.sync(SHOP_ID, MARKETPLACE, null, null));
        assertTrue(ex.getMessage().contains("轮询超时"), ex.getMessage());
        verify(spApiFinanceClient, times(3)).getReport("RPT-3", SHOP_ID);
    }

    @Test
    @DisplayName("空报表：不报错但给出「可能确实没有结算」提示")
    void emptyReportWarns() {
        stubHappyReportPath(HEADER + "\n");
        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(0, report.getDataLineCount());
        assertEquals(0, report.getInserted());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("无数据行")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("行级错误：脏行进报告但不影响其余行入库")
    void rowErrorsReportedWithoutBlocking() {
        String mixed = HEADER + "\n"
                + row("USD", "Order", "111-1", "SKU-A", "Principal", "29.99") + "\n"
                + row("USD", "Order", "111-2", "SKU-B", "Principal", "oops") + "\n";
        stubHappyReportPath(mixed);
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(new ArrayList<>());
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenReturn(1);

        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(2, report.getDataLineCount());
        assertEquals(1, report.getInserted());
        assertEquals(1, report.getRowErrors().size());
        assertEquals(0, report.getFailed(), "解析期错误计入 rowErrors，不是落库失败");
    }

    @Test
    @DisplayName("多币种：给出不可跨币种求和的显式提示")
    void multiCurrencyWarning() {
        String mixed = HEADER + "\n"
                + row("USD", "Order", "111-1", "SKU-A", "Principal", "29.99") + "\n"
                + row("EUR", "Order", "111-2", "SKU-B", "Principal", "19.99") + "\n";
        stubHappyReportPath(mixed);
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(new ArrayList<>());
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenReturn(1);

        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(2, report.getCurrencies().size());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("多币种")),
                report.getWarnings().toString());
    }

    @Test
    @DisplayName("落库异常：计入 failed 并进入行级错误，不中断整批")
    void insertFailureCounted() {
        String twoRows = HEADER + "\n"
                + row("USD", "Order", "111-1", "SKU-A", "Principal", "29.99") + "\n"
                + row("USD", "Order", "111-2", "SKU-B", "Principal", "10.00") + "\n";
        stubHappyReportPath(twoRows);
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(new ArrayList<>());
        when(settlementDetailMapper.insert(any(SettlementDetail.class)))
                .thenThrow(new RuntimeException("Duplicate entry"))
                .thenReturn(1);

        SettlementIngestReport report = settlementService.sync(SHOP_ID, MARKETPLACE, null, null);

        assertEquals(1, report.getInserted());
        assertEquals(1, report.getFailed());
        assertEquals(1, report.getRowErrors().size());
    }

    @Test
    @DisplayName("list：按店铺查询，订单号为空时不加过滤条件")
    void listDelegates() {
        List<SettlementDetail> rows = new ArrayList<>();
        SettlementDetail d = new SettlementDetail();
        d.setOrderId("111-1");
        rows.add(d);
        when(settlementDetailMapper.selectList(any())).thenReturn(rows);

        assertEquals(1, settlementService.list(SHOP_ID, "111-1").size());
        assertEquals(1, settlementService.list(SHOP_ID, null).size());
        verify(settlementDetailMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("shopId 为空：抛 IllegalArgumentException")
    void shopIdRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> settlementService.sync(null, MARKETPLACE, null, null));
        assertThrows(IllegalArgumentException.class, () -> settlementService.list(null, null));
    }
}
