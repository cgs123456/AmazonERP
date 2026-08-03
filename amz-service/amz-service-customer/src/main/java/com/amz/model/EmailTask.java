package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 自动化邮件任务实体。
 */
@Data
@TableName("amz_email_task")
public class EmailTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private Long templateId;
    private String amazonOrderId;
    private String asin;
    private String buyerEmail;
    private String buyerName;
    private String subject;
    private String body;
    private String status;
    private LocalDateTime scheduledTime;
    private LocalDateTime sentTime;
    private String failureReason;
    private String source;
}
