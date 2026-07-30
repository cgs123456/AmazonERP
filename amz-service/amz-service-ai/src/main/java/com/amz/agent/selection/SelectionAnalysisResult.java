package com.amz.agent.selection;

/**
 * 选品分析结果 DTO（AI 服务侧）。
 * <p>
 * 包含 DeepSeek 生成的摘要与详细建议（是否进入/定价策略/差异化方向/首批采购量）。
 */
public class SelectionAnalysisResult {

    /** AI 分析摘要（一段话总结） */
    private String aiSummary;

    /** AI 详细建议：是否进入/定价策略/差异化方向/首批采购量 */
    private String aiSuggestion;

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getAiSuggestion() {
        return aiSuggestion;
    }

    public void setAiSuggestion(String aiSuggestion) {
        this.aiSuggestion = aiSuggestion;
    }
}
