package com.amz.client;

import com.amz.client.dto.ReportInfo;

/**
 * SP-API Reports API（2021-09-01）客户端。
 * <p>
 * 覆盖报表三段式流程：
 * <ol>
 *   <li>{@link #createReport} —— 创建报表请求，返回 reportId</li>
 *   <li>{@link #getReport} —— 轮询处理状态（processingStatus）</li>
 *   <li>{@link #downloadDocument} —— 下载结果文档（自动 GZIP 解压）</li>
 * </ol>
 * 轮询节奏由调用方控制（本接口不阻塞等待），便于上层做超时与降级。
 */
public interface ReportsClient {

    /**
     * 创建报表请求。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId 目标 Marketplace ID
     * @param reportType    报表类型（如 GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE）
     * @param dataStartTime 数据起始时间（ISO 8601，含时区）
     * @param dataEndTime   数据截止时间（ISO 8601，含时区）
     * @return reportId
     */
    String createReport(Long shopId, String marketplaceId, String reportType,
                        String dataStartTime, String dataEndTime);

    /**
     * 查询报表处理状态。
     *
     * @param shopId   店铺 ID
     * @param reportId 报表 ID
     * @return 报表状态（DONE 时携带 documentId）
     */
    ReportInfo getReport(Long shopId, String reportId);

    /**
     * 下载报表结果文档内容（文本，自动按文档元数据解压）。
     *
     * @param shopId     店铺 ID
     * @param documentId 结果文档 ID（来自 {@link ReportInfo#getDocumentId()}）
     * @return 解压后的文档文本（结算原表为 TSV）
     */
    String downloadDocument(Long shopId, String documentId);
}
