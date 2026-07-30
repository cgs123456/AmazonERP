package com.amz.agent.selection;

/**
 * 选品分析服务接口。
 * <p>
 * 调用 DeepSeek 生成选品建议，输出是否进入市场、定价策略、差异化方向和首批采购量。
 */
public interface SelectionAnalysisService {

    /**
     * 分析选品机会，生成 AI 摘要与详细建议。
     *
     * @param input 选品机会数据（市场指标 + 竞争指标 + 趋势）
     * @return 分析结果（ai_summary + ai_suggestion）
     */
    SelectionAnalysisResult analyzeOpportunity(SelectionOpportunityInput input);
}
