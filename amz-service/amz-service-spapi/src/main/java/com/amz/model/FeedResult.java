package com.amz.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds processing report 的结构化结果。
 * <p>
 * {@code -1} 表示报告中缺少对应 summary 字段；调用方必须 fail-closed，不得把未知值当作 0。
 * 报告正文不进入本对象，只保留必要的计数与规范化 issue。
 */
public final class FeedResult {

    private final String feedId;
    private final String resultFeedDocumentId;
    private final int messagesProcessed;
    private final int messagesAccepted;
    private final int messagesInvalid;
    private final int errors;
    private final int warnings;
    private final List<FeedIssue> issues;

    public FeedResult(String feedId, String resultFeedDocumentId,
                      int messagesProcessed, int messagesAccepted, int messagesInvalid,
                      int errors, int warnings, List<FeedIssue> issues) {
        this.feedId = feedId;
        this.resultFeedDocumentId = resultFeedDocumentId;
        this.messagesProcessed = messagesProcessed;
        this.messagesAccepted = messagesAccepted;
        this.messagesInvalid = messagesInvalid;
        this.errors = errors;
        this.warnings = warnings;
        this.issues = issues == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(issues));
    }

    public String getFeedId() {
        return feedId;
    }

    public String getResultFeedDocumentId() {
        return resultFeedDocumentId;
    }

    public int getMessagesProcessed() {
        return messagesProcessed;
    }

    public int getMessagesAccepted() {
        return messagesAccepted;
    }

    public int getMessagesInvalid() {
        return messagesInvalid;
    }

    public int getErrors() {
        return errors;
    }

    public int getWarnings() {
        return warnings;
    }

    public List<FeedIssue> getIssues() {
        return issues;
    }

    public boolean hasErrors() {
        if (errors > 0) {
            return true;
        }
        return issues.stream().anyMatch(FeedIssue::isError);
    }

    public boolean hasWarnings() {
        if (warnings > 0) {
            return true;
        }
        return issues.stream().anyMatch(FeedIssue::isWarning);
    }

    /**
     * 只有报告明确给出至少一个 accepted 且没有 invalid/ERROR 时才算完整成功。
     */
    public boolean isSuccessful() {
        return messagesAccepted > 0 && messagesInvalid == 0 && !hasErrors();
    }

    /**
     * 有成功行但也有拒绝行时是部分成功。
     */
    public boolean isPartial() {
        return messagesAccepted > 0 && (messagesInvalid > 0 || hasErrors());
    }

    /**
     * 没有任何 accepted 行且报告明确处理过消息或有 ERROR 时是失败。
     */
    public boolean isFailed() {
        return messagesAccepted == 0 && (messagesProcessed > 0 || hasErrors());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("feedId", feedId);
        map.put("resultFeedDocumentId", resultFeedDocumentId);
        map.put("messagesProcessed", messagesProcessed);
        map.put("messagesAccepted", messagesAccepted);
        map.put("messagesInvalid", messagesInvalid);
        map.put("errors", errors);
        map.put("warnings", warnings);
        map.put("successful", isSuccessful());
        map.put("partial", isPartial());
        map.put("failed", isFailed());
        map.put("issues", issues.stream().map(issue -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rowIndex", issue.getRowIndex());
            item.put("sellerSku", issue.getSellerSku());
            item.put("errorCode", issue.getErrorCode());
            item.put("severity", issue.getSeverity());
            item.put("errorMessage", issue.getErrorMessage());
            return item;
        }).toList());
        return map;
    }
}
