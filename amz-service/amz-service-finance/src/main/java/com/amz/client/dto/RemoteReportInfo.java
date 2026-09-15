package com.amz.client.dto;

import lombok.Data;

/**
 * amz-service-spapi 返回的报表状态（本地镜像 DTO）。
 * 字段名与 spapi 侧 {@code ReportInfo} 一致，构成跨服务 JSON 契约。
 */
@Data
public class RemoteReportInfo {

    private String reportId;
    private String reportType;
    /** IN_QUEUE / IN_PROGRESS / DONE / FATAL / CANCELLED。 */
    private String processingStatus;
    private String documentId;

    public boolean isDone() {
        return "DONE".equals(processingStatus);
    }

    public boolean isTerminal() {
        return "DONE".equals(processingStatus)
                || "FATAL".equals(processingStatus)
                || "CANCELLED".equals(processingStatus);
    }
}
