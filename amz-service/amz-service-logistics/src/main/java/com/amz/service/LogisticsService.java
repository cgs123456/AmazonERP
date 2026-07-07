package com.amz.service;

import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;

import java.util.List;

/**
 * 物流追踪服务接口。
 */
public interface LogisticsService {

    /**
     * 创建头程物流单 / FBA 货件。
     */
    Shipment createShipment(Shipment shipment);

    /**
     * 查询店铺货件列表。
     */
    List<Shipment> listShipments(Long shopId, String status);

    /**
     * 同步货件状态（拉取承运商最新轨迹，更新货件状态）。
     */
    Shipment syncShipmentStatus(Long shipmentId);

    /**
     * 查询货件的完整轨迹（用于前端轨迹可视化）。
     * 返回按时间正序排列的轨迹点列表（含经纬度）。
     */
    List<TrackingEvent> getTrackingTimeline(Long shipmentId);
}
