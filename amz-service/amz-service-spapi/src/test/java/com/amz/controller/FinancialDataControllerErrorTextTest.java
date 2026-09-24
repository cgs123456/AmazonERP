package com.amz.controller;

import com.amz.client.ReportsClient;
import com.amz.client.dto.ReportInfo;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-52a / P0-53 的边界层验收：财务域 controller 的失败响应必须
 * ① 带平台错误信息（不能被固定文案吞掉）；② 不泄露预签名 URL 的签名与凭证。
 * <p>
 * 本测试故意让桩件抛出「带完整预签名 URL 的异常文本」——即最坏情况（上游没有做收敛）。
 * 边界层必须自己兜住：先取根因、再脱敏、最后压成单行。
 * <p>
 * 证据类型 E1（自证，{@link ReportsClient} 被桩件替换）。不构成 A1–A8 证据，
 * 也不证明 Amazon 侧的响应形态——那是 A5 联调的范围。
 */
@DisplayName("P0-52a/P0-53 边界层：财务域错误文本可诊断且不泄露")
class FinancialDataControllerErrorTextTest {

    private static final String PRESIGNED_URL =
            "https://bucket.s3.amazonaws.com/report/2026-09-24/settlement.tsv"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260924%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260924T000000Z&X-Amz-Expires=300&X-Amz-SignedHeaders=host"
                    + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";

    private static FinancialDataController controllerWith(ReportsClient reportsClient) {
        FinancialDataController controller = new FinancialDataController();
        ReflectionTestUtils.setField(controller, "reportsClient", reportsClient);
        return controller;
    }

    @Test
    @DisplayName("下载失败：状态码与对象路径保留，签名值与 AccessKey 不出现在响应文本")
    void downloadFailureSurfacesStatusWithoutSignature() {
        ReportsClient reportsClient = mock(ReportsClient.class);
        when(reportsClient.downloadDocument(anyLong(), anyString()))
                .thenThrow(new RuntimeException("download failed status=403 url=" + PRESIGNED_URL));

        Result<String> result = controllerWith(reportsClient).downloadDocument("doc-1", 1001L);

        assertEquals(400, result.getCode());
        String message = result.getMessage();
        assertTrue(message.contains("status=403"), message);
        assertTrue(message.contains("/report/2026-09-24/settlement.tsv"), message);
        assertTrue(message.contains("X-Amz-Signature=***"), message);
        assertFalse(message.contains("deadbeef"), message);
        assertFalse(message.contains("AKIAEXAMPLEKEY"), message);
        assertFalse(message.contains("%2F20260924"), message);
    }

    @Test
    @DisplayName("degraded 包装：响应给平台 errors 原文（status + code），不把包装文案当结论")
    void degradedWrapperSurfacesPlatformCode() {
        RuntimeException platform = new RuntimeException(
                "SP-API call failed method=GET path=/reports/2021-09-01/reports/r-1 status=429"
                        + " body={\"errors\":[{\"code\":\"QuotaExceeded\",\"message\":\"Request is throttled\"}]}");
        RuntimeException degraded = new RuntimeException(
                "getReport degraded (circuit-breaker/exception) shopId=1001", platform);
        ReportsClient reportsClient = mock(ReportsClient.class);
        when(reportsClient.getReport(anyLong(), anyString())).thenThrow(degraded);

        Result<ReportInfo> result = controllerWith(reportsClient).getReport("r-1", 1001L);

        assertEquals(400, result.getCode());
        String message = result.getMessage();
        assertTrue(message.contains("status=429"), message);
        assertTrue(message.contains("QuotaExceeded"), message);
        assertFalse(message.contains("degraded"), message);
    }

    @Test
    @DisplayName("缺凭证：显式报错（A2 口径），不得静默返回空数据")
    void missingCredentialIsExplicit() {
        ReportsClient reportsClient = mock(ReportsClient.class);
        when(reportsClient.getReport(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("No credential found for shopId=1001"));

        Result<ReportInfo> result = controllerWith(reportsClient).getReport("r-1", 1001L);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("No credential found for shopId=1001"), result.getMessage());
    }
}