package com.amz.mapper;

import com.amz.dto.ShipmentDeliveredTime;
import com.amz.model.TrackingEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TrackingEventMapper extends BaseMapper<TrackingEvent> {

    /**
     * 批量查询各货件的送达事件时间（取最新一条）。
     * <p>
     * <b>为何不逐货件查：</b>看板要算平均头程时效，若对每个已送达货件各发一条查询，
     * 一个中等规模的店铺就会产生上百次往返（N+1）。此处用一次 GROUP BY 批量取回。
     * <p>
     * {@code MAX(event_time)} 直接对字符串取最大值是可行的：该列在落库时已统一归一为
     * {@code yyyy-MM-dd HH:mm:ss}（UTC），字典序等于时间序。若存在历史遗留的非规范值，
     * 最坏结果是该货件时效计算偏小，不会影响其他货件，也不会导致查询报错。
     */
    @Select("<script>"
            + "SELECT shipment_id AS shipmentId, MAX(event_time) AS deliveredTime "
            + "FROM amz_tracking_event "
            + "WHERE event_status = 'DELIVERED' AND shipment_id IN "
            + "<foreach item='id' collection='shipmentIds' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY shipment_id"
            + "</script>")
    List<ShipmentDeliveredTime> selectDeliveredTimes(@Param("shipmentIds") Collection<Long> shipmentIds);
}
