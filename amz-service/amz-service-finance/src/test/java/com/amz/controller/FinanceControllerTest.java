package com.amz.controller;

import com.amz.dto.KingdeeSyncResult;
import com.amz.dto.ProcurementVoucherReport;
import com.amz.result.Result;
import com.amz.service.FinanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceControllerTest {

    @Mock
    private FinanceService financeService;

    @InjectMocks
    private FinanceController controller;

    @Test
    @DisplayName("金蝶同步失败：业务码 400，同时保留机器可读 FAILED 结果")
    void syncFailureReturnsBusinessFailureWithResult() {
        KingdeeSyncResult syncResult = KingdeeSyncResult.failed(9L, "金蝶网关超时");
        when(financeService.syncToKingdee(9L)).thenReturn(syncResult);

        Result<KingdeeSyncResult> result = controller.syncToKingdee(9L);

        assertEquals(400, result.getCode());
        assertEquals(syncResult, result.getData());
        assertEquals(KingdeeSyncResult.Status.FAILED, result.getData().status());
    }

    @Test
    @DisplayName("金蝶模拟同步：HTTP 业务成功但结果明确标记 MOCK，不冒充真实入账")
    void syncMockReturnsExplicitMockResult() {
        KingdeeSyncResult syncResult = KingdeeSyncResult.mock(10L, "KINGDEE_MOCK_1");
        when(financeService.syncToKingdee(10L)).thenReturn(syncResult);

        Result<KingdeeSyncResult> result = controller.syncToKingdee(10L);

        assertEquals(200, result.getCode());
        assertEquals(KingdeeSyncResult.Status.MOCK, result.getData().status());
        assertEquals(false, result.getData().isRealSuccess());
    }

    @Test
    @DisplayName("金蝶未配置：返回 NOT_CONFIGURED，不伪装成调用失败或成功")
    void syncNotConfiguredReturnsExplicitStatus() {
        KingdeeSyncResult syncResult = KingdeeSyncResult.notConfigured(11L, "缺少 kingdee.app-secret");
        when(financeService.syncToKingdee(11L)).thenReturn(syncResult);

        Result<KingdeeSyncResult> result = controller.syncToKingdee(11L);

        assertEquals(400, result.getCode());
        assertEquals(KingdeeSyncResult.Status.NOT_CONFIGURED, result.getData().status());
    }
    @Test
    @DisplayName("采购凭证：采购域读不到时返回业务失败，但 data 仍带完整报告")
    void procurementVoucherDegradedIsBusinessFailureWithReport() {
        ProcurementVoucherReport report = new ProcurementVoucherReport();
        report.setShopId(7L);
        report.setRemoteDegraded(true);
        report.setRemoteMessage("procurement service degraded: connection refused");
        when(financeService.generateProcurementVouchers(7L)).thenReturn(report);

        Result<ProcurementVoucherReport> result = controller.generateProcurementVouchers(7L);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("采购域数据不完整"), result.getMessage());
        assertEquals(report, result.getData());
        assertEquals(0, result.getData().getGenerated(), "降级时一张凭证都不该生成");
    }

    @Test
    @DisplayName("采购凭证：读取正常时业务成功并透出计数")
    void procurementVoucherSuccessCarriesCounts() {
        ProcurementVoucherReport report = new ProcurementVoucherReport();
        report.setShopId(7L);
        report.setScanned(3);
        report.setGenerated(2);
        report.setExisting(1);
        when(financeService.generateProcurementVouchers(7L)).thenReturn(report);

        Result<ProcurementVoucherReport> result = controller.generateProcurementVouchers(7L);

        assertEquals(200, result.getCode());
        assertEquals(2, result.getData().getGenerated());
        assertEquals(1, result.getData().getExisting());
    }
}
