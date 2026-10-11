package com.amz.client.dto;

import lombok.Data;

/**
 * 报表状态（logistics 侧镜像 DTO）。
 * <p>
 * 字段名与 spapi 的 {@code ReportInfo} 严格一致——两侧各自持有 DTO 而不是共享模块类，
 * 与仓库既有跨服务约定一致（避免 logistics 依赖 spapi 的模块类）。
 */
@Data
public class RemoteReportInfo {

    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FATAL = "FATAL";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private String reportId;
    private String reportType;
    /** IN_QUEUE / IN_PROGRESS / DONE / FATAL / CANCELLED。 */
    private String processingStatus;
    private String createdTime;
    private String documentId;

    public boolean isDone() {
        return STATUS_DONE.equals(processingStatus);
    }

    public boolean isTerminal() {
        return STATUS_DONE.equals(processingStatus)
                || STATUS_FATAL.equals(processingStatus)
                || STATUS_CANCELLED.equals(processingStatus);
    }
}
