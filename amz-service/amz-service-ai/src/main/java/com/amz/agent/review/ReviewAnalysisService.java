package com.amz.agent.review;

import java.util.List;

/**
 * 评论分析服务接口。
 * <p>
 * 对 Amazon 商品评论进行痛点聚类、情感分析和改进建议生成，
 * 供 Agent 工具调用和 REST 接口使用。
 */
public interface ReviewAnalysisService {

    /**
     * 分析一批评论，返回结构化分析结果。
     *
     * @param reviews 评论列表
     * @return 分析结果（情感得分、痛点、建议、总结）
     */
    ReviewAnalysisResult analyze(List<ReviewInfo> reviews);
}
