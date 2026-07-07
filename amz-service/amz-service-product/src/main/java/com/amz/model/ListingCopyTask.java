package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Listing 跨站点复制任务。
 * 状态流转：PENDING -> PROCESSING -> SUBMITTED -> SUCCESS / FAILED
 */
@Data
@TableName("amz_listing_copy_task")
public class ListingCopyTask implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("shop_id")
    private Long shopId;

    @TableField("source_marketplace_id")
    private String sourceMarketplaceId;

    @TableField("target_marketplace_id")
    private String targetMarketplaceId;

    @TableField("sku")
    private String sku;

    @TableField("source_title")
    private String sourceTitle;

    @TableField("source_price")
    private BigDecimal sourcePrice;

    @TableField("target_title")
    private String targetTitle;

    @TableField("target_price")
    private BigDecimal targetPrice;

    @TableField("target_language")
    private String targetLanguage;

    @TableField("exchange_rate")
    private BigDecimal exchangeRate;

    @TableField("price_markup")
    private BigDecimal priceMarkup;

    @TableField("status")
    private String status;

    @TableField("feed_submission_id")
    private String feedSubmissionId;

    @TableField("error_message")
    private String errorMessage;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
