package com.amz.agent.review;

import lombok.Data;

import java.util.List;

/**
 * 评论分析结果 DTO，包含情感分析、痛点聚类、改进建议等。
 */
@Data
public class ReviewAnalysisResult {

    /**
     * 整体情感得分（0-100，越高越正面）
     */
    private Double sentimentScore;

    /**
     * 痛点聚类列表（如 "电池续航不足"、"包装破损" 等）
     */
    private List<String> painPoints;

    /**
     * 改进建议列表
     */
    private List<String> suggestions;

    /**
     * 综合总结
     */
    private String summary;
}
