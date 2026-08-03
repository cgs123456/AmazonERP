package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("amz_webhook_event")
public class WebhookEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String platform;
    private String eventType;
    private String eventId;
    private String payload;
    private String status;
    private String processResult;
    private LocalDateTime processTime;
    private LocalDateTime createTime;
}
