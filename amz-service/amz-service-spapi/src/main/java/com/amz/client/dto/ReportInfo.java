package com.amz.client.dto;

import lombok.Data;

/**
 * SP-API 报表状态（Reports API 2021-09-01）。
 */
@Data
public class ReportInfo {

    /** 处理中（已入队）。 */
    public static final String STATUS_IN_QUEUE = "IN_QUEUE";
    /** 处理中。 */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** 处理完成，可下载。 */
    public static final String STATUS_DONE = "DONE";
    /** 处理失败（不可恢复）。 */
    public static final String STATUS_FATAL = "FATAL";
    /** 已取消。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    private String reportId;
    private String reportType;
    /** IN_QUEUE / IN_PROGRESS / DONE / FATAL / CANCELLED。 */
    private String processingStatus;
    /** 处理完成后的结果文档 ID（未完成时为 null）。 */
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
