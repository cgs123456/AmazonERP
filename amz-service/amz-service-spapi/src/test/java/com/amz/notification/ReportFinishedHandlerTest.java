package com.amz.notification;

import com.amz.client.ReportsClient;
import com.amz.client.ReportsMockClient;
import com.amz.client.dto.ReportInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ReportFinishedHandler 装配与行为契约。
 * <p>
 * <b>装配（本类的存在动因）</b>：Handler 必须依赖 {@link ReportsClient} 接口。
 * {@code ReportsRealClient} 带 {@code @Profile("!mock")}、{@code ReportsMockClient} 带
 * {@code @Profile("mock")}，两者都实现 {@link ReportsClient}。Handler 若直接依赖具体的
 * {@code ReportsRealClient}，则 {@code spring.profiles.active=mock} 下容器里只有
 * {@code ReportsMockClient}，报表处理器会因 NoSuchBeanDefinition 让整个 spapi 服务起不来
 * （2026-09-30 P2 压测基线实测到的启动失败；runtime_smoke 默认就是 mock profile）。
 * <p>
 * 剩余用例锁住 handle 的下载分支：只有 DONE 才下载，且只在拿到 documentId 时下载。
 */
@DisplayName("ReportFinishedHandler：依赖 ReportsClient 接口而非真实客户端")
class ReportFinishedHandlerTest {

    private static final Long SHOP_ID = 7L;
    private static final String REPORT_ID = "r-1";
    private static final String DOCUMENT_ID = "doc-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("构造函数只接受 ReportsClient 接口（否则 mock profile 下无法装配）")
    void constructorDependsOnInterface() {
        assertNotNull(assertDoesNotThrow(() ->
                        ReportFinishedHandler.class.getConstructor(ReportsClient.class)),
                "构造函数参数必须是 ReportsClient 接口，否则 mock profile 下 spapi 起不来");
    }

    @Test
    @DisplayName("mock profile 的真实 Bean（ReportsMockClient）可直接装配")
    void acceptsMockProfileBean() {
        assertDoesNotThrow(() -> new ReportFinishedHandler(new ReportsMockClient()));
    }

    @Test
    @DisplayName("类型匹配：只认 REPORT_PROCESSING_FINISHED")
    void supportsOnlyReportFinished() {
        ReportFinishedHandler handler = new ReportFinishedHandler(mock(ReportsClient.class));
        assertTrue(handler.supports("REPORT_PROCESSING_FINISHED"));
        assertTrue(handler.supports("report_processing_finished"));
        assertFalse(handler.supports("ORDER_CHANGE"));
        assertFalse(handler.supports(null));
    }

    @Test
    @DisplayName("DONE：按 shopId 取报表并下载文档")
    void downloadsDocumentWhenDone() throws Exception {
        ReportsClient client = mock(ReportsClient.class);
        ReportInfo info = new ReportInfo();
        info.setReportId(REPORT_ID);
        info.setDocumentId(DOCUMENT_ID);
        when(client.getReport(SHOP_ID, REPORT_ID)).thenReturn(info);
        when(client.downloadDocument(SHOP_ID, DOCUMENT_ID)).thenReturn("tsv-content");

        new ReportFinishedHandler(client).handle(context("""
                {"ReportProcessingFinishedNotification":{"reportId":"r-1","processingStatus":"DONE"}}
                """));

        verify(client).getReport(SHOP_ID, REPORT_ID);
        verify(client).downloadDocument(SHOP_ID, DOCUMENT_ID);
    }

    @Test
    @DisplayName("非终态：不下载文档")
    void skipsWhenNotDone() throws Exception {
        ReportsClient client = mock(ReportsClient.class);

        new ReportFinishedHandler(client).handle(context("""
                {"ReportProcessingFinishedNotification":{"reportId":"r-1","processingStatus":"IN_PROGRESS"}}
                """));

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("DONE 但无 documentId：只告警不下载")
    void skipsWhenDocumentIdMissing() throws Exception {
        ReportsClient client = mock(ReportsClient.class);
        ReportInfo info = new ReportInfo();
        info.setReportId(REPORT_ID);
        when(client.getReport(SHOP_ID, REPORT_ID)).thenReturn(info);

        new ReportFinishedHandler(client).handle(context("""
                {"ReportProcessingFinishedNotification":{"reportId":"r-1","processingStatus":"DONE"}}
                """));

        verify(client).getReport(SHOP_ID, REPORT_ID);
        verify(client, never()).downloadDocument(anyLong(), anyString());
    }

    private NotificationContext context(String payloadJson) throws Exception {
        JsonNode payload = objectMapper.readTree(payloadJson);
        return new NotificationContext(1L, "notif-1", ReportFinishedHandler.TYPE,
                LocalDateTime.of(2026, 9, 28, 10, 0, 0),
                LocalDateTime.of(2026, 9, 28, 10, 0, 1),
                SHOP_ID, "ATVPDKIKX0DER", "sub-1", "1.0", false, payload, payloadJson);
    }
}