package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知历史（消息中心落库，V5）。
 * <p>
 * 写入方是 message 服务（推送时经 Feign 落库，在线/离线都落）；
 * 读取方是本服务的 {@code GET /user/notification/list}。
 */
@Data
@TableName("amz_notification")
public class Notification implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 消息类型（MessageTypeEnum 序号）。 */
    private Integer type;

    private String title;

    private String content;

    /** 0=未读 1=已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}
