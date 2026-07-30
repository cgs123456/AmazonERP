package com.amz.service;

import com.amz.model.KeywordResearch;
import com.amz.result.Result;

/**
 * 选品服务接口：发现蓝海产品、分析市场趋势、评估竞争程度。
 * <p>
 * 基于关键词与 ASIN 进行 Helium 10 / Jungle Scout 式的 8 维度市场分析，
 * 结合 AI 生成选品建议。
 */
public interface ProductSelectionService {

    /**
     * 分析关键词市场，生成 Top N 蓝海机会并落库。
     *
     * @param keyword     关键词（如 wireless earbuds）
     * @param marketplace 站点代码（US/UK/DE/JP 等）
     * @return 含分析摘要与机会列表的结果
     */
    Result analyzeMarket(String keyword, String marketplace);

    /**
     * 发现蓝海机会列表，按指定字段排序。
     *
     * @param shopId   店铺 ID
     * @param category 品类过滤，可为空
     * @param sortBy   排序字段：score/volume/competition，默认 score
     * @param limit    返回条数上限
     * @return 机会列表
     */
    Result findOpportunities(Long shopId, String category, String sortBy, int limit);

    /**
     * 竞品分析：基于指定 ASIN 输出竞品列表与差异化建议。
     *
     * @param asin       目标 ASIN
     * @param marketplace 站点代码
     * @return 竞品分析结果
     */
    Result analyzeCompetitors(String asin, String marketplace);

    /**
     * 关键词调研：返回搜索量、点击份额、转化份额、竞争难度等指标。
     *
     * @param keyword     关键词
     * @param marketplace 站点代码
     * @return 关键词调研结果
     */
    Result<KeywordResearch> researchKeyword(String keyword, String marketplace);

    /**
     * 调用 AI 生成选品建议，回填到机会记录。
     *
     * @param opportunityId 机会记录 ID
     * @return 含 ai_summary 与 ai_suggestion 的结果
     */
    Result aiSuggestion(Long opportunityId);
}
