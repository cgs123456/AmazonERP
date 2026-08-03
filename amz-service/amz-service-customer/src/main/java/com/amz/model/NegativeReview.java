package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 差评监控实体。
 */
@Data
@TableName("amz_negative_review")
public class NegativeReview implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String amazonOrderId;
    private String asin;
    private String reviewerName;
    private Integer reviewRating;
    private String reviewTitle;
    private String reviewContent;
    private LocalDate reviewDate;
    private String reviewId;
    private Integer verifiedPurchase;
    private String status;
    private String matchedOrderId;
    private Long contactEmailTaskId;
}
