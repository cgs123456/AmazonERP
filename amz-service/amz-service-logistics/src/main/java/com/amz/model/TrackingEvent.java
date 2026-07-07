package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 物流轨迹点实体（用于轨迹可视化）。
 * 每条记录代表一个物流状态变更节点，前端按时间顺序连成轨迹链路。
 */
@Data
@TableName("amz_tracking_event")
public class TrackingEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联货件 ID */
    private Long shipmentId;

    /** 事件状态码：CREATED/DEPARTED/IN_TRANSIT/CUSTOMS_CLEARANCE/ARRIVED/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION */
    private String eventStatus;

    /** 事件发生地点 */
    private String location;

    /** 事件描述 */
    private String description;

    /** 事件发生时间（ISO 格式字符串） */
    private String eventTime;

    /** 经度（轨迹可视化用） */
    private Double longitude;

    /** 纬度（轨迹可视化用） */
    private Double latitude;
}
