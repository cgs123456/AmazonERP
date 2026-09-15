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

    /**
     * 数据来源：MOCK / IMPORT / API。
     * <p>
     * 双入口并存时用于区分数据血缘：IMPORT 为外部爬取后经导入接口灌入，
     * API 为第三方聚合接口拉取，MOCK 为离线模拟。便于排查某条轨迹由哪条链路写入。
     */
    private String source;

    /**
     * 承运商原始状态文本（映射为 {@link #eventStatus} 之前的值）。
     * <p>
     * 保留原始文本用于两处：一是映射字典命中失败时回溯排查，二是
     * 后续发现某承运商新状态词时据此补映射规则，无需重新抓取。
     */
    private String rawStatus;
}
