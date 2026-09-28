package com.amz.model;

import java.util.Objects;

/**
 * Feeds processing report 中的单条 issue（拒绝或警告行）。
 * <p>
 * 只保留业务诊断必需字段，不保存 processing report 原文或整行原始 JSON。
 */
public final class FeedIssue {

    private final int rowIndex;
    private final String sellerSku;
    private final String errorCode;
    private final String severity;
    private final String errorMessage;

    public FeedIssue(int rowIndex, String sellerSku, String errorCode,
                     String severity, String errorMessage) {
        this.rowIndex = rowIndex;
        this.sellerSku = sellerSku;
        this.errorCode = errorCode;
        this.severity = severity == null || severity.isBlank() ? "ERROR" : severity;
        this.errorMessage = errorMessage;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public String getSellerSku() {
        return sellerSku;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getSeverity() {
        return severity;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isError() {
        return "ERROR".equalsIgnoreCase(severity);
    }

    public boolean isWarning() {
        return "WARNING".equalsIgnoreCase(severity);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FeedIssue issue)) {
            return false;
        }
        return rowIndex == issue.rowIndex
                && Objects.equals(sellerSku, issue.sellerSku)
                && Objects.equals(errorCode, issue.errorCode)
                && Objects.equals(severity, issue.severity)
                && Objects.equals(errorMessage, issue.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rowIndex, sellerSku, errorCode, severity, errorMessage);
    }
}
