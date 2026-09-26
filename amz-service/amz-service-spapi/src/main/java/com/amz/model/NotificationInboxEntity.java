package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SP-API 入站通知账本。
 * <p>
 * 两条硬规则对应两个字段设计：
 * <ul>
 *   <li>先落库再删 SQS 消息 → SQS standard queue 的重复投递由 {@code notification_id} 唯一键吸收，
 *       重复投递记 {@code duplicate_count}，若哈希不同还要额外告警（可能是 Amazon 侧重发不同内容）。</li>
 *   <li>顺序不保证 → 业务层必须比较 {@code event_time}，不能假设后到的一定更新。</li>
 * </ul>
 * {@code payload_encrypted} 只能存密文：订单类通知正文可能含买家姓名与地址。
 */
@Data
@TableName("amz_spapi_notification_inbox")
public class NotificationInboxEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("notification_id")
    private String notificationId;

    @TableField("notification_type")
    private String notificationType;

    @TableField("payload_version")
    private String payloadVersion;

    @TableField("event_time")
    private LocalDateTime eventTime;

    @TableField("publish_time")
    private LocalDateTime publishTime;

    @TableField("shop_id")
    private Long shopId;

    @TableField("marketplace_id")
    private String marketplaceId;

    @TableField("application_id")
    private String applicationId;

    @TableField("subscription_id")
    private String subscriptionId;

    @TableField("destination_id")
    private String destinationId;

    /** AES-256-GCM 密文；不落明文，不进日志。 */
    @TableField("payload_encrypted")
    private String payloadEncrypted;

    @TableField("payload_sha256")
    private String payloadSha256;

    @TableField("payload_bytes")
    private Integer payloadBytes;

    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("max_attempts")
    private Integer maxAttempts;

    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_until")
    private LocalDateTime leaseUntil;

    @TableField("last_error_code")
    private String lastErrorCode;

    @TableField("last_error_message")
    private String lastErrorMessage;

    @TableField("duplicate_count")
    private Integer duplicateCount;

    @TableField("synthetic")
    private Integer synthetic;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("processed_at")
    private LocalDateTime processedAt;
}