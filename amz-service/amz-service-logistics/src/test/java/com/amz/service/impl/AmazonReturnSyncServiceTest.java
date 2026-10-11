package com.amz.service.impl;

import com.amz.client.SpApiReportsClient;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.exception.CodeErrorException;
import com.amz.model.ReturnOrder;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Amazon FBA 退货同步契约。
 * <p>
 * <b>存在动因</b>：退货单此前只能手工登记。本测试钉住三件「错了会静默做坏事」的事：
 * <ol>
 *   <li><b>超时不静默返空</b>——报表没就绪必须抛错，「没拉到」不能记成「本期无退货」；</li>
 *   <li><b>FATAL/CANCELLED 不下载</b>——终态失败还去下载文档只会拿到空/脏内容；</li>
 *   <li><b>逐行导入并计数</b>——已存在的行计入 skipped 而不是重复建单。</li>
 * </ol>
 * 变异验证（写本测试时实际注入确认会红）：把超时分支改成返回空字符串 → 第一例红；
 * 把 FATAL 也当 DONE 处理 → 第二例红。
 */
@DisplayName("Amazon 退货同步：超时不返空 + 终态失败不下载 + 逐行幂等导入")
class AmazonReturnSyncServiceTest {

    private static final String HEADER = String.join("\t",
            "return-date", "order-id", "sku", "asin", "fnsku", "product-name", "quantity",
            "fulfillment-center-id", "detailed-disposition", "reason", "status",
            "license-plate-number", "customer-comments");

    private final SpApiReportsClient client = mock(SpApiReportsClient.class);
    private final ReturnOrderService returnOrderService = mock(ReturnOrderService.class);

    @Test
    @DisplayName("成功路径：拉取→解析→逐行导入，统计正确")
    void syncsAndImportsRows() {
        AmazonReturnSyncService service = newService();
        when(client.requestReport(any(), anyString(), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-1"));
        RemoteReportInfo done = new RemoteReportInfo();
        done.setProcessingStatus("DONE");
        done.setDocumentId("DOC-1");
        when(client.getReport(eq("RPT-1"), any())).thenReturn(Result.success(done));
        when(client.downloadDocument(eq("DOC-1"), any())).thenReturn(Result.success(tsv()));
        when(returnOrderService.importAmazonReturn(any(ReturnOrder.class)))
                .thenReturn(true, false);

        AmazonReturnSyncService.SyncResult result = service.syncShop(9001L, "ATVPDKIKX0DER", 7);

        assertEquals(2, result.fetched());
        assertEquals(1, result.imported());
        assertEquals(1, result.skipped(), "已存在的行计入 skipped，不重复建单");
        assertEquals(0, result.rowErrors());
        verify(client).downloadDocument(eq("DOC-1"), any());
    }

    @Test
    @DisplayName("报表未就绪：抛错，绝不返回空报表当成功")
    void doesNotReturnEmptyOnTimeout() {
        AmazonReturnSyncService service = newService();
        ReflectionTestUtils.setField(service, "maxPollAttempts", 2);
        ReflectionTestUtils.setField(service, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(service, "pollDeadlineMs", 1L);
        when(client.requestReport(any(), anyString(), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-1"));
        RemoteReportInfo inProgress = new RemoteReportInfo();
        inProgress.setProcessingStatus("IN_PROGRESS");
        when(client.getReport(eq("RPT-1"), any())).thenReturn(Result.success(inProgress));

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.syncShop(9001L, "ATVPDKIKX0DER", 7));

        assertTrue(e.getMessage().contains("未在限制内就绪"),
                "必须点名未就绪，实际：" + e.getMessage());
        verify(client, never()).downloadDocument(anyString(), any());
        verify(returnOrderService, never()).importAmazonReturn(any(ReturnOrder.class));
    }

    @Test
    @DisplayName("报表 FATAL：抛错且不下载文档")
    void doesNotDownloadOnFatal() {
        AmazonReturnSyncService service = newService();
        when(client.requestReport(any(), anyString(), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-1"));
        RemoteReportInfo fatal = new RemoteReportInfo();
        fatal.setProcessingStatus("FATAL");
        when(client.getReport(eq("RPT-1"), any())).thenReturn(Result.success(fatal));

        assertThrows(CodeErrorException.class, () -> service.syncShop(9001L, "ATVPDKIKX0DER", 7));
        verify(client, never()).downloadDocument(anyString(), any());
    }

    @Test
    @DisplayName("createReport 失败：抛错，不进入轮询")
    void failsWhenRequestRejected() {
        AmazonReturnSyncService service = newService();
        when(client.requestReport(any(), anyString(), anyString(), any(), any()))
                .thenReturn(Result.failure("no credential"));

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.syncShop(9001L, "ATVPDKIKX0DER", 7));
        assertTrue(e.getMessage().contains("创建退货报表请求失败"));
        verify(client, never()).getReport(anyString(), any());
    }

    @Test
    @DisplayName("DONE 但缺 documentId：抛错而不是下载空文档")
    void failsWhenDocumentIdMissing() {
        AmazonReturnSyncService service = newService();
        when(client.requestReport(any(), anyString(), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-1"));
        RemoteReportInfo doneNoDoc = new RemoteReportInfo();
        doneNoDoc.setProcessingStatus("DONE");
        when(client.getReport(eq("RPT-1"), any())).thenReturn(Result.success(doneNoDoc));

        assertThrows(CodeErrorException.class, () -> service.syncShop(9001L, "ATVPDKIKX0DER", 7));
        verify(client, never()).downloadDocument(anyString(), any());
    }

    @Test
    @DisplayName("无 spapi 通道：明确失败")
    void failsWithoutChannel() {
        AmazonReturnSyncService service = new AmazonReturnSyncService();
        ReflectionTestUtils.setField(service, "returnOrderService", returnOrderService);
        // spApiReportsClient 保持 null

        assertThrows(CodeErrorException.class, () -> service.syncShop(9001L, "ATVPDKIKX0DER", 7));
    }

    @Test
    @DisplayName("行级错误可见但不中断整批（好行仍导入）")
    void toleratesRowErrors() {
        AmazonReturnSyncService service = newService();
        when(client.requestReport(any(), anyString(), anyString(), any(), any()))
                .thenReturn(Result.success("RPT-1"));
        RemoteReportInfo done = new RemoteReportInfo();
        done.setProcessingStatus("DONE");
        done.setDocumentId("DOC-1");
        when(client.getReport(eq("RPT-1"), any())).thenReturn(Result.success(done));
        String dirty = HEADER + "\n"
                + row("2026-10-01", "114-1", "SKU-1", "1", "SELLABLE") + "\n"
                + row("2026-10-01", "114-2", "SKU-2", "abc", "SELLABLE");
        when(client.downloadDocument(eq("DOC-1"), any())).thenReturn(Result.success(dirty));
        when(returnOrderService.importAmazonReturn(any(ReturnOrder.class))).thenReturn(true);

        AmazonReturnSyncService.SyncResult result = service.syncShop(9001L, "ATVPDKIKX0DER", 7);

        assertEquals(1, result.fetched(), "好行保留");
        assertEquals(1, result.rowErrors(), "脏行记账");
        verify(returnOrderService, times(1)).importAmazonReturn(any(ReturnOrder.class));
    }

    // ============ 辅助 ============

    private AmazonReturnSyncService newService() {
        AmazonReturnSyncService service = new AmazonReturnSyncService();
        ReflectionTestUtils.setField(service, "spApiReportsClient", client);
        ReflectionTestUtils.setField(service, "returnOrderService", returnOrderService);
        ReflectionTestUtils.setField(service, "maxPollAttempts", 3);
        ReflectionTestUtils.setField(service, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(service, "pollDeadlineMs", 5000L);
        return service;
    }

    private String tsv() {
        return HEADER + "\n"
                + row("2026-10-01", "114-1", "SKU-1", "1", "SELLABLE") + "\n"
                + row("2026-10-02", "114-2", "SKU-2", "2", "DEFECTIVE");
    }

    private String row(String returnDate, String orderId, String sku, String qty, String disposition) {
        return String.join("\t", returnDate, orderId, sku, "B0X", "X1", "商品", qty,
                "FBA1", disposition, "r", "s", "LPN", "c");
    }
}
