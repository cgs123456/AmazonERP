package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SP-API 通知目标（destination）与店铺映射。
 * <p>
 * 存在的理由：SQS 消息里没有 shop_id，只有 subscriptionId / applicationId；
 * 没有这张表就无法判断事件归属，多店铺场景会直接串店。
 */
@Data
@TableName("amz_spapi_notification_destination")
public class NotificationDestinationEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("destination_id")
    private String destinationId;

    @TableField("shop_id")
    private Long shopId;

    @TableField("marketplace_id")
    private String marketplaceId;

    /** SQS 或 EVENT_BRIDGE。 */
    @TableField("resource_type")
    private String resourceType;

    @TableField("queue_arn")
    private String queueArn;

    @TableField("event_bus_arn")
    private String eventBusArn;

    @TableField("region")
    private String region;

    @TableField("status")
    private String status;

    /** 1 表示合成演练数据，生产库必须为 0。 */
    @TableField("synthetic")
    private Integer synthetic;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}