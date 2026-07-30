package com.amz.agent.review;

import lombok.Data;

/**
 * 评论信息 DTO，作为评论分析的输入。
 */
@Data
public class ReviewInfo {

    /**
     * 评分（1-5 星）
     */
    private Integer rating;

    /**
     * 评论标题
     */
    private String title;

    /**
     * 评论正文
     */
    private String content;

    /**
     * 评论日期（yyyy-MM-dd）
     */
    private String date;

    /**
     * 是否 VP（Verified Purchase）真实购买
     */
    private Boolean verifiedPurchase;
}
