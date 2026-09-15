package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.client.LogisticsTrackingClient;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.service.LogisticsService;
import com.amz.service.TrackingIngestService;
import com.amz.util.BizNoGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 物流追踪服务实现。
 */
@Slf4j
@Service
public class LogisticsServiceImpl implements LogisticsService {

    private static final String STATUS_CLOSED = "CLOSED";

    @Autowired
    private ShipmentMapper shipmentMapper;

    @Autowired
    private TrackingEventMapper trackingEventMapper;

    @Autowired
    private LogisticsTrackingClient trackingClient;

    /**
     * 统一落库核心。手动 sync / 外部导入 / 定时拉取三条路径全部经此写入，
     * 保证状态映射与幂等去重只有一份实现。
     */
    @Autowired
    private TrackingIngestService trackingIngestService;

    @Override
    public Shipment createShipment(Shipment shipment) {
        shipment.setShipmentNo(BizNoGenerator.next("SHP"));
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
    @Transactional(rollbackFor = Exception.class)
    public Shipment syncShipmentStatus(Long shipmentId) {
        Shipment shipment = requireAccessible(shipmentId);
        // 拉取承运商最新轨迹后交由统一落库核心处理：
        // 状态映射、幂等合并、取数时间刷新均与「外部导入」入口共用同一实现。
        // 改造前此处为「先删后插」，双入口场景下会抹掉导入侧写入的历史轨迹，故移除。
        List<TrackingEvent> events = trackingClient.queryTracking(
                shipment.getMasterTrackingNo(), shipment.getCarrier());
        trackingIngestService.ingest(shipment.getShipmentNo(), shipment.getMasterTrackingNo(),
                events, TrackingIngestService.SOURCE_API, shipment.getShopId());
        return shipmentMapper.selectById(shipmentId);
    }

    @Override
    public List<TrackingEvent> getTrackingTimeline(Long shipmentId) {
        // 先校验归属再取轨迹：轨迹没带店铺字段，一旦取出来就已经越过租户边界了
        requireAccessible(shipmentId);

        LambdaQueryWrapper<TrackingEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TrackingEvent::getShipmentId, shipmentId)
                .orderByAsc(TrackingEvent::getEventTime);
        List<TrackingEvent> events = trackingEventMapper.selectList(wrapper);
        // 按事件时间正序（最早在前，便于前端绘制轨迹链路）
        events.sort(Comparator.comparing(TrackingEvent::getEventTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Shipment closeShipment(Long shipmentId, Long shopId) {
        Shipment shipment = requireAccessible(shipmentId);
        if (shopId != null && shipment.getShopId() != null && !shopId.equals(shipment.getShopId())) {
            // 显式传入的 shopId 与货件归属不一致：可能是前端传错，也可能是构造请求越权，
            // 两种情形都拒绝并记录，不做「以某一方为准」的猜测
            log.warn("关单请求的 shopId 与货件归属不一致：userId={} shipmentId={} 入参shopId={} 货件shopId={}",
                    UserContext.getUserId(), shipmentId, shopId, shipment.getShopId());
            throw new CodeErrorException("货件不属于当前店铺");
        }
        if (STATUS_CLOSED.equals(shipment.getStatus())) {
            // 幂等：重复关单直接返回当前状态，不报错（用户重复点击不应看到失败）
            return shipment;
        }
        log.info("手工关闭货件：userId={} shipmentId={} 原状态={}",
                UserContext.getUserId(), shipmentId, shipment.getStatus());
        shipment.setStatus(STATUS_CLOSED);
        shipmentMapper.updateById(shipment);
        return shipmentMapper.selectById(shipmentId);
    }

    // ------------------------------------------------------------------ 归属校验

    /**
     * 按 ID 取货件并校验归属。
     * <p>
     * <b>为什么必须有这一步：</b>货件 ID 来自请求路径，之前只按 ID 查询，
     * 任何已登录用户改一下数字就能读到别家店铺的轨迹时间线——一次典型的越权读取（IDOR）。
     * 轨迹表本身不含店铺字段，数据一旦被取出，租户边界就已经被越过了，故校验必须前置。
     * <p>
     * 信任模型与 {@code @ShopScoped} 切面保持一致：{@code UserContext} 无 shops 时跳过校验，
     * 以兼容内部调用与白名单场景（如 AI 工具经 Feign 调用）。
     * <p>
     * 提示语统一为「货件不存在或无权访问」，不区分两种情况——
     * 否则可用该接口探测他店货件编号是否存在。
     */
    private Shipment requireAccessible(Long shipmentId) {
        Shipment shipment = shipmentMapper.selectById(shipmentId);
        if (shipment == null) {
            throw new CodeErrorException("货件不存在或无权访问");
        }
        if (!UserContext.isShopAllowed(shipment.getShopId())) {
            log.warn("货件越权访问拦截：userId={} shipmentId={} 货件店铺={}",
                    UserContext.getUserId(), shipmentId, shipment.getShopId());
            throw new CodeErrorException("货件不存在或无权访问");
        }
        return shipment;
    }
}
