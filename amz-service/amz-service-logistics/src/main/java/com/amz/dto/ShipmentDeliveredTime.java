package com.amz.dto;

import lombok.Data;

/**
 * 货件「承运商确认送达」时间（聚合查询结果）。
 * <p>
 * 用于计算平均头程时效：终点取轨迹中 {@code DELIVERED} 事件的<b>事件时间</b>，
 * 而不是货件行的更新时间。后者是「系统某次同步的时刻」，与真实送达时刻可能相差数天，
 * 用它算出来的时效会系统性偏向「同步频率」而非「实际运输快慢」。
 * <p>
 * 之所以能直接对 {@code event_time} 取 MAX：该字段虽为字符串，
 * 但落库时已统一归一为 {@code yyyy-MM-dd HH:mm:ss}（UTC），字典序即时间序。
 */
@Data
public class ShipmentDeliveredTime {

    /** 货件 ID */
    private Long shipmentId;

    /** 该货件下最新的送达事件时间 */
    private String deliveredTime;
}
