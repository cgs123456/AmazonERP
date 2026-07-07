package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步日志实体。
 * <p>
 * 对应 amz_spapi.amz_inventory_sync_log 表，记录每次库存/订单同步的执行结果。
 */
@Data
@TableName("amz_inventory_sync_log")
public class InventorySyncLog {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 店铺 ID。
     */
    @TableField("shop_id")
    private Long shopId;

    /**
     * 同步类型：INVENTORY/ORDERS。
     */
    @TableField("sync_type")
    private String syncType;

    /**
     * 同步状态：SUCCESS/FAILED。
     */
    @TableField("status")
    private String status;

    /**
     * 同步记录数。
     */
    @TableField("records_synced")
    private Integer recordsSynced;

    /**
     * 错误信息（失败时填）。
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 开始时间。
     */
    @TableField("start_time")
    private LocalDateTime startTime;

    /**
     * 结束时间。
     */
    @TableField("end_time")
    private LocalDateTime endTime;
}
