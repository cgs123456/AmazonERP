package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.client.LogisticsTrackingClient;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.LogisticsService;
import com.amz.service.TrackingIngestService;
import com.amz.util.BizNoGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import static com.amz.service.impl.LogisticsShopGuard.requireShopAllowed;

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
        if (shipment == null) {
            throw new CodeErrorException("货件内容不能为空");
        }
        requireShopAllowed(shipment.getShopId(), "货件");
        shipment.setShipmentNo(BizNoGenerator.next("SHP"));
        // 创建入口只能产生初始态，客户端不能伪造 DELIVERED / CLOSED 等终态
        shipment.setStatus("CREATED");
        shipmentMapper.insert(shipment);
        return shipment;
    }

    @Override
    public PageResult<Shipment> listShipments(Long shopId, String status, PageRequest page) {
        requireShopAllowed(shopId, "货件");
        requirePage(page);
        LambdaQueryWrapper<Shipment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shipment::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Shipment::getStatus, status);
        }
        Long cursorId = page.cursorId();
        if (cursorId != null) {
            wrapper.lt(Shipment::getId, cursorId);
        }
        wrapper.orderByDesc(Shipment::getId)
                .last("LIMIT " + page.probeSize());
        return PageResult.of(shipmentMapper.selectList(wrapper), page.size(),
                item -> PageRequest.encodeCursor(item.getId()));
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
    public PageResult<TrackingEvent> getTrackingTimeline(Long shipmentId, PageRequest page) {
        // 先校验归属再取轨迹：轨迹没带店铺字段，一旦取出来就已经越过租户边界了
        requireAccessible(shipmentId);
        requirePage(page);

        LambdaQueryWrapper<TrackingEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TrackingEvent::getShipmentId, shipmentId);
        TrackingCursor cursor = parseTrackingCursor(page);
        if (cursor != null) {
            if (cursor.nullEventTime()) {
                wrapper.isNull(TrackingEvent::getEventTime)
                        .gt(TrackingEvent::getId, cursor.id());
            } else {
                // 非空时间按 (eventTime,id) 继续；NULL 段排在最后，因此也要纳入续扫范围。
                wrapper.and(w -> w
                        .gt(TrackingEvent::getEventTime, cursor.eventTime())
                        .or()
                        .eq(TrackingEvent::getEventTime, cursor.eventTime())
                        .gt(TrackingEvent::getId, cursor.id())
                        .or()
                        .isNull(TrackingEvent::getEventTime));
            }
        }
        // MySQL 默认 NULL 在最前；显式把 NULL 桶放到最后，并用 id 打破同时间并列。
        wrapper.last("ORDER BY (event_time IS NULL) ASC, event_time ASC, id ASC LIMIT "
                + page.probeSize());
        return PageResult.of(trackingEventMapper.selectList(wrapper), page.size(),
                this::encodeTrackingCursor);
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

    // ------------------------------------------------------------------ 轨迹游标

    private TrackingCursor parseTrackingCursor(PageRequest page) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String payload = page.payload();
        int firstSeparator = payload.indexOf('|');
        if (firstSeparator <= 0 || firstSeparator == payload.length() - 1) {
            throw invalidTrackingCursor(payload);
        }
        String kind = payload.substring(0, firstSeparator);
        if ("N".equals(kind)) {
            if (payload.indexOf('|', firstSeparator + 1) >= 0) {
                throw invalidTrackingCursor(payload);
            }
            return new TrackingCursor(true, null, parseCursorId(payload, firstSeparator + 1));
        }
        if (!"V".equals(kind)) {
            throw invalidTrackingCursor(payload);
        }
        int lastSeparator = payload.lastIndexOf('|');
        if (lastSeparator <= firstSeparator + 1 || lastSeparator == payload.length() - 1) {
            throw invalidTrackingCursor(payload);
        }
        String eventTime = payload.substring(firstSeparator + 1, lastSeparator);
        if (eventTime.isBlank()) {
            throw invalidTrackingCursor(payload);
        }
        return new TrackingCursor(false, eventTime, parseCursorId(payload, lastSeparator + 1));
    }

    private long parseCursorId(String payload, int start) {
        try {
            long id = Long.parseLong(payload.substring(start));
            if (id <= 0) {
                throw invalidTrackingCursor(payload);
            }
            return id;
        } catch (NumberFormatException e) {
            throw invalidTrackingCursor(payload);
        }
    }

    private InvalidParamException invalidTrackingCursor(String payload) {
        return new InvalidParamException("轨迹分页游标非法：" + payload);
    }

    private String encodeTrackingCursor(TrackingEvent event) {
        if (event.getId() == null || event.getId() <= 0) {
            throw new InvalidParamException("轨迹分页游标生成失败：id 缺失");
        }
        if (event.getEventTime() == null || event.getEventTime().isBlank()) {
            return PageRequest.encodeCursor("N|" + event.getId());
        }
        return PageRequest.encodeCursor("V|" + event.getEventTime() + "|" + event.getId());
    }

    private record TrackingCursor(boolean nullEventTime, String eventTime, long id) {
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

    private void requirePage(PageRequest page) {
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
    }

}
