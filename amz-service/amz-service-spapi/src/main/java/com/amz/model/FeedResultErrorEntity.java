package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Feeds processing report 的规范化错误/警告行。
 * <p>
 * 不保存未加密报告正文或原始行 JSON，避免把 Amazon 报告中的潜在敏感内容落盘。
 */
@Data
@TableName("amz_feed_result_error")
public class FeedResultErrorEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("feed_id")
    private String feedId;

    @TableField("shop_id")
    private Long shopId;

    @TableField("issue_index")
    private Integer issueIndex;

    @TableField("row_index")
    private Integer rowIndex;

    @TableField("seller_sku")
    private String sellerSku;

    @TableField("error_code")
    private String errorCode;

    @TableField("severity")
    private String severity;

    @TableField("error_message")
    private String errorMessage;

    @TableField("create_time")
    private LocalDateTime createTime;
}
