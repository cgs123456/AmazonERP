package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SP-API 通知订阅与店铺映射。
 * <p>
 * 查不到映射的事件必须进入 UNRESOLVED_SUBSCRIPTION 隔离：
 * 猜归属会造成跨店串数据，丢弃会造成丢单，两者都比「卡住等人工补映射」更贵。
 */
@Data
@TableName("amz_spapi_notification_subscription")
public class NotificationSubscriptionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("subscription_id")
    private String subscriptionId;

    @TableField("destination_id")
    private String destinationId;

    @TableField("shop_id")
    private Long shopId;

    @TableField("marketplace_id")
    private String marketplaceId;

    @TableField("notification_type")
    private String notificationType;

    @TableField("payload_version")
    private String payloadVersion;

    @TableField("filter_expression")
    private String filterExpression;

    /** 官方 eventFilter；当前仅三类通知类型支持，其余为 null。 */
    @TableField("event_filter")
    private String eventFilter;

    @TableField("status")
    private String status;

    @TableField("synthetic")
    private Integer synthetic;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
