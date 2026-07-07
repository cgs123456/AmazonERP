package com.amz.service.impl;

import com.amz.client.LogisticsTrackingClient;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.service.LogisticsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 物流追踪服务实现。
 */
@Slf4j
@Service
public class LogisticsServiceImpl implements LogisticsService {

    @Autowired
    private ShipmentMapper shipmentMapper;

    @Autowired
    private TrackingEventMapper trackingEventMapper;

    @Autowired
    private LogisticsTrackingClient trackingClient;

    @Override
    public Shipment createShipment(Shipment shipment) {
        shipment.setShipmentNo("SHP" + System.currentTimeMillis());
        if (shipment.getStatus() == null) {
            shipment.setStatus("CREATED");
        }
        shipmentMapper.insert(shipment);
        return shipment;
    }

    @Override
    public List<Shipment> listShipments(Long shopId, String status) {
        LambdaQueryWrapper<Shipment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shipment::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Shipment::getStatus, status);
        }
        wrapper.orderByDesc(Shipment::getId);
        return shipmentMapper.selectList(wrapper);
    }

    @Override
    public Shipment syncShipmentStatus(Long shipmentId) {
        Shipment shipment = shipmentMapper.selectById(shipmentId);
        if (shipment == null) {
            throw new IllegalArgumentException("货件不存在：id=" + shipmentId);
        }
        // 拉取承运商最新轨迹
        List<TrackingEvent> events = trackingClient.queryTracking(
                shipment.getMasterTrackingNo(), shipment.getCarrier());
        if (events == null || events.isEmpty()) {
            return shipment;
        }
        // 最新事件的状态映射到货件状态
        TrackingEvent latest = events.get(0);
        shipment.setStatus(mapEventToShipmentStatus(latest.getEventStatus()));
        shipmentMapper.updateById(shipment);

        // 持久化轨迹点（先删后插，避免重复）
        LambdaQueryWrapper<TrackingEvent> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(TrackingEvent::getShipmentId, shipmentId);
        trackingEventMapper.delete(deleteWrapper);
        for (TrackingEvent e : events) {
            e.setShipmentId(shipmentId);
            trackingEventMapper.insert(e);
        }
        log.info("货件轨迹同步：shipmentId={} 轨迹点 {} 个", shipmentId, events.size());
        return shipment;
    }

    @Override
    public List<TrackingEvent> getTrackingTimeline(Long shipmentId) {
        LambdaQueryWrapper<TrackingEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TrackingEvent::getShipmentId, shipmentId)
                .orderByAsc(TrackingEvent::getEventTime);
        List<TrackingEvent> events = trackingEventMapper.selectList(wrapper);
        // 按事件时间正序（最早在前，便于前端绘制轨迹链路）
        events.sort(Comparator.comparing(TrackingEvent::getEventTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    /**
     * 将承运商事件状态映射到货件整体状态。
     */
    private String mapEventToShipmentStatus(String eventStatus) {
        if (eventStatus == null) return "CREATED";
        switch (eventStatus) {
            case "CREATED":      return "CREATED";
            case "DEPARTED":
            case "IN_TRANSIT":   return "IN_TRANSIT";
            case "CUSTOMS_CLEARANCE": return "CUSTOMS";
            case "ARRIVED":
            case "OUT_FOR_DELIVERY": return "DELIVERED";
            case "DELIVERED":    return "RECEIVED";
            case "EXCEPTION":    return "EXCEPTION";
            default:             return "IN_TRANSIT";
        }
    }
}
