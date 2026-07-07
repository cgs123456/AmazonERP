package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 差评监控告警实体。
 * 当某 ASIN 新增 1-3 星差评时触发告警，便于运营及时介入（沟通买家/分析原因）。
 */
@Data
@TableName("amz_negative_review_alert")
public class NegativeReviewAlert implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 商品 ASIN */
    private String asin;

    /** Amazon 评论 ID */
    private String reviewId;

    /** 评分 1-5 */
    private Integer rating;

    /** 评论标题 */
    private String title;

    /** 评论内容 */
    private String content;

    /** 买家名称 */
    private String reviewer;

    /** 状态：NEW / HANDLED / IGNORED */
    private String status;
}
