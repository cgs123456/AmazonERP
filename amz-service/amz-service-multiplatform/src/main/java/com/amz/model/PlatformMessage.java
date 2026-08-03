package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("amz_platform_message")
public class PlatformMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String platform;
    private String platformMessageId;
    private String buyerName;
    private String buyerEmail;
    private String orderId;
    private String subject;
    private String content;
    private String direction;
    private String status;
    private String assignedTo;
    private Boolean isUrgent;
    private LocalDateTime receiveTime;
    private LocalDateTime replyTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
