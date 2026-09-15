package com.amz.service.impl;

import com.amz.dto.IngestOutcome;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.service.TrackingIngestService;
import com.amz.service.TrackingStatusMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轨迹落库实现。
 * <p>
 * <b>与旧实现的差异（重要）：</b>原先 {@code LogisticsServiceImpl.syncShipmentStatus} 采用
 * 「先删该货件全部轨迹、再把新轨迹插入」的策略。该策略在「每条运单只有一个数据源」时成立，
 * 但在双入口场景下会互相破坏：第三方 API 只返回近期轨迹时，一次拉取会把此前由外部导入
 * 灌入的完整历史轨迹整段删除。本实现改为按 {@code (eventStatus, eventTime)} 指纹做增量合并，
 * 两条入口的写入只会追加互补、不会互相覆盖。
 */
@Slf4j
@Service
public class TrackingIngestServiceImpl implements TrackingIngestService {

    /**
     * 已无跟踪价值的货件状态：不参与延误判定，也不再需要拉取轨迹。
     * <p>
     * {@code DELIVERED} 一并纳入的原因：承运商确认送达后，头程环节已经结束，
     * 后续的「FBA 是否签收入库」由 FBA 入库渠道提供，承运商轨迹再也带不来新信息。
     * 若把它留在跟踪范围内，已送达的货件会被调度永久轮询，白耗第三方配额。
     */
    private static final Set<String> TERMINAL_STATUSES = Set.of("DELIVERED", "RECEIVED", "CLOSED");

    private static final String STATUS_DELAYED = "DELAYED";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 轨迹事件时间的统一存储格式（UTC）。
     * <p>
     * 选这个格式而非 ISO-8601 带时区串，是因为该字段在库中为字符串，
     * 排序与「取最新事件」都直接依赖字典序；而带偏移的串（{@code +08:00} 与 {@code -07:00}）
     * 字典序与真实时间先后不一致，混入后会把最新事件判错。
     * 统一为无偏移的固定宽度格式后，字典序即时间序。
     */
    private static final DateTimeFormatter CANONICAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ShipmentMapper shipmentMapper;

    @Autowired
    private TrackingEventMapper trackingEventMapper;

    @Autowired
    private TrackingStatusMapper statusMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestOutcome ingest(String shipmentNo, String trackingNo, List<TrackingEvent> events,
                               String source, Long shopId) {
        Shipment shipment = resolveShipment(shipmentNo, trackingNo, shopId);
        if (shipment == null) {
            log.warn("轨迹落库未匹配到货件，已回吐给调用方：shipmentNo={} trackingNo={} source={} shopId={}",
                    shipmentNo, trackingNo, source, shopId);
            return IngestOutcome.unmatched();
        }

        IngestOutcome outcome = IngestOutcome.matched(shipment.getId());
        if (events == null || events.isEmpty()) {
            // 已定位到货件但本次无轨迹（典型情形：API 查询成功但对端暂无数据）：
            // 仍需刷新取数时间，否则调度会因「看起来很久没更新」而反复选中同一批货件
            touchLastTrackTime(shipment);
            outcome.setShipmentStatus(shipment.getStatus());
            return outcome;
        }

        List<TrackingEvent> normalized = normalize(events, shipment.getId(), source);
        if (normalized.isEmpty()) {
            touchLastTrackTime(shipment);
            outcome.setShipmentStatus(shipment.getStatus());
            return outcome;
        }

        Set<String> fingerprints = loadFingerprints(shipment.getId());
        int accepted = 0;
        int skipped = 0;
        for (TrackingEvent event : normalized) {
            String fingerprint = fingerprintOf(event);
            if (!fingerprints.add(fingerprint)) {
                skipped++;
                continue;
            }
            trackingEventMapper.insert(event);
            accepted++;
        }

        String mappedStatus = toShipmentStatus(latestOf(normalized).getEventStatus());
        String previous = shipment.getStatus();
        boolean changed = !mappedStatus.equals(previous);
        // 已判定延误的货件不因一条普通轨迹点回退为在途状态：延误是需要人工关注的信号，
        // 只有真正推进到下一环节（签收/入库）才解除
        if (changed && !(STATUS_DELAYED.equals(previous) && "IN_TRANSIT".equals(mappedStatus))) {
            shipment.setStatus(mappedStatus);
        } else {
            changed = false;
        }
        // 状态未变也要落库：取数时间是调度轮换的依据，漏更新会导致同一货件被反复拉取
        shipment.setLastTrackTime(LocalDateTime.now());
        shipmentMapper.updateById(shipment);

        outcome.setAccepted(accepted);
        outcome.setSkipped(skipped);
        outcome.setStatusChanged(changed);
        outcome.setShipmentStatus(shipment.getStatus());

        log.info("轨迹落库完成：shipmentId={} source={} 新增={} 跳过={} 状态 {}→{}",
                shipment.getId(), source, accepted, skipped, previous, shipment.getStatus());
        return outcome;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markDelayedShipments(int graceDays) {
        LocalDate threshold = LocalDate.now().minusDays(Math.max(0, graceDays));
        List<Shipment> candidates = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                .notIn(Shipment::getStatus, TERMINAL_STATUSES)
                .isNotNull(Shipment::getEta));

        int marked = 0;
        for (Shipment shipment : candidates) {
            if (STATUS_DELAYED.equals(shipment.getStatus())) {
                continue;
            }
            LocalDate eta = parseDate(shipment.getEta());
            if (eta == null || !eta.isBefore(threshold)) {
                continue;
            }
            log.info("ETA 超期判定延误：shipmentId={} eta={} 阈值={}", shipment.getId(), eta, threshold);
            shipment.setStatus(STATUS_DELAYED);
            shipmentMapper.updateById(shipment);
            marked++;
        }
        return marked;
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 定位货件：优先货件编号（唯一约束），退化为主运单号。
     * <p>
     * 用 selectList 取首条而非 selectOne：主运单号未建唯一索引，
     * 数据异常时 selectOne 会抛异常中断整批导入，而取首条能让其余记录继续落库。
     * <p>
     * {@code shopId} 非空时追加店铺条件：货件编号与运单号均来自外部输入，
     * 不加此条件则可用他店运单号把轨迹写入他店货件。
     */
    private Shipment resolveShipment(String shipmentNo, String trackingNo, Long shopId) {
        if (shipmentNo != null && !shipmentNo.isBlank()) {
            List<Shipment> byNo = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                    .eq(Shipment::getShipmentNo, shipmentNo.trim())
                    .eq(shopId != null, Shipment::getShopId, shopId));
            if (!byNo.isEmpty()) {
                return byNo.get(0);
            }
        }
        if (trackingNo != null && !trackingNo.isBlank()) {
            List<Shipment> byTracking = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                    .eq(Shipment::getMasterTrackingNo, trackingNo.trim())
                    .eq(shopId != null, Shipment::getShopId, shopId));
            if (!byTracking.isEmpty()) {
                return byTracking.get(0);
            }
        }
        return null;
    }

    /**
     * 归一化：状态映射 + 时间规整 + 补来源与归属 + 清主键。
     * <p>
     * 清主键是必要的：导入方若从旧库导出并带上 id，直接 insert 会与既有行冲突或被误认为更新，
     * 轨迹点的主键一律由数据库生成。
     * <p>
     * 时间规整同样是必要的：轨迹点的排序、「最新事件」判定、以及去重指纹都建立在
     * {@code eventTime} 的字符串比较之上，而各来源给的时间形态完全不同
     * （17track 给带时区偏移的 ISO 串、爬取数据给 {@code yyyy-MM-dd HH:mm:ss}）。
     * 不统一则有两处后果——带不同偏移的事件按字符串排序会得出错误的时间先后，
     * 同一事件换个时区写法就会被当成两条新轨迹重复入库。
     */
    private List<TrackingEvent> normalize(List<TrackingEvent> events, Long shipmentId, String source) {
        List<TrackingEvent> result = new ArrayList<>(events.size());
        for (TrackingEvent event : events) {
            if (event == null) {
                continue;
            }
            String rawText = (event.getRawStatus() != null && !event.getRawStatus().isBlank())
                    ? event.getRawStatus() : event.getEventStatus();
            event.setEventStatus(statusMapper.toInternal(event.getRawStatus(), event.getEventStatus()));
            event.setRawStatus(rawText);
            event.setEventTime(canonicalTime(event.getEventTime()));
            event.setSource(source);
            event.setShipmentId(shipmentId);
            event.setId(null);
            result.add(event);
        }
        return result;
    }

    /**
     * 将事件时间规整为统一的 {@code yyyy-MM-dd HH:mm:ss}（UTC）字符串，便于直接按字典序比较。
     * <p>
     * 依次尝试：带偏移的 ISO-8601（{@code 2026-08-05T09:00:00+08:00} / {@code ...Z}）、
     * 不带时区的 ISO 本地时间、已规整格式、纯日期。
     * <p>
     * <b>无时区信息时一律按 UTC 解释</b>，而非按服务器时区：后者会让同一份数据在不同机器上
     * 得到不同结果，多实例部署时同一事件可能被去重判定为两条。
     * <p>
     * <b>无法识别时原样保留</b>，不返回 null、不丢弃该轨迹点——时间不可解析只是排序不准，
     * 而轨迹点丢失会让看板的时效统计直接失真，后者严重得多。
     */
    private String canonicalTime(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String text = value.trim();

        // 带时区偏移（含 Z）：换算到 UTC 后落成统一格式
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).format(CANONICAL_FMT);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他形态
        }
        // ISO 本地时间（T 分隔，无时区）
        try {
            return LocalDateTime.parse(text).format(CANONICAL_FMT);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他形态
        }
        // 已是规整格式
        try {
            return LocalDateTime.parse(text, CANONICAL_FMT).format(CANONICAL_FMT);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他形态
        }
        // 纯日期：补零点
        try {
            return LocalDate.parse(text, DATE_FMT).atStartOfDay().format(CANONICAL_FMT);
        } catch (DateTimeParseException ignored) {
            log.debug("事件时间格式无法识别，按原值保留（可能影响排序）：{}", value);
            return text;
        }
    }

    /** 读取该货件已有轨迹指纹，用于增量合并判定 */
    private Set<String> loadFingerprints(Long shipmentId) {
        List<TrackingEvent> existing = trackingEventMapper.selectList(
                new LambdaQueryWrapper<TrackingEvent>().eq(TrackingEvent::getShipmentId, shipmentId));
        Set<String> fingerprints = new HashSet<>(Math.max(16, existing.size() * 2));
        for (TrackingEvent event : existing) {
            fingerprints.add(fingerprintOf(event));
        }
        return fingerprints;
    }

    /**
     * 轨迹点指纹。
     * <p>
     * 正常以「状态 + 事件时间」判重；事件时间缺失时退化为「状态 + 地点 + 描述」，
     * 避免所有无时间事件被压成同一条而误判为重复。
     */
    private String fingerprintOf(TrackingEvent event) {
        String status = event.getEventStatus() == null ? "" : event.getEventStatus();
        String time = event.getEventTime();
        if (time != null && !time.isBlank()) {
            return status + '|' + time.trim();
        }
        String location = event.getLocation() == null ? "" : event.getLocation();
        String description = event.getDescription() == null ? "" : event.getDescription();
        return status + '|' + location + '|' + description;
    }

    /** 取事件时间最大的轨迹点；时间缺失时退化为列表首条 */
    private TrackingEvent latestOf(List<TrackingEvent> events) {
        return events.stream()
                .max(Comparator.comparing(TrackingEvent::getEventTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(events.get(0));
    }

    /**
     * 轨迹状态 → 货件整体状态。
     * <p>
     * <b>修正说明（本次调整）：</b>改造前 {@code ARRIVED / OUT_FOR_DELIVERY} 一并映射为
     * {@code DELIVERED}、{@code DELIVERED} 又映射为 {@code RECEIVED}。该口径偏乐观，
     * 会把「到港」「派送中」算作已送达，使时效统计提前收敛、延误判定再也不会触发——
     * 一个卡在港口一个月的货件会被看板显示成「已按时送达」。这对新建的时效看板是致命的，
     * 因为它让问题数据看起来正常。故按「承运商视角推进到什么环节」重新对齐：
     * <ul>
     *   <li>{@code ARRIVED} 到港 → 仍在途中（尚未送达，也不等于已清关）</li>
     *   <li>{@code OUT_FOR_DELIVERY} 派送中 → 仍在途中（派送中可以失败）</li>
     *   <li>{@code DELIVERED} 承运商确认送达 → {@code DELIVERED}</li>
     * </ul>
     * <b>关于 RECEIVED：</b>它表示 FBA 已签收入库，属于 FBA 入库渠道的信息，
     * 不应由承运商轨迹推断——承运商说「送到仓库门口」不等于亚马逊已入库验收。
     * 该状态由 FBA 签收差异流程写入；同时它也是调度停止跟踪的终态之一。
     */
    private String toShipmentStatus(String eventStatus) {
        if (eventStatus == null) {
            return "CREATED";
        }
        switch (eventStatus) {
            case "CREATED":
                return "CREATED";
            case "DEPARTED":
            case "IN_TRANSIT":
            case "ARRIVED":
            case "OUT_FOR_DELIVERY":
                return "IN_TRANSIT";
            case "CUSTOMS_CLEARANCE":
                return "CUSTOMS";
            case "DELIVERED":
                return "DELIVERED";
            case "EXCEPTION":
                return "EXCEPTION";
            default:
                return "IN_TRANSIT";
        }
    }

    /**
     * 刷新货件最近取数时间并落库。
     * <p>
     * 单独抽出是为了覆盖「已定位货件但本次无轨迹」的提前返回分支：
     * 该字段语义是「刚刚确认过」，而不是「有新数据」——调度靠它判断哪些货件最久未被查看，
     * 若只在有新轨迹时才刷新，无数据的货件会被每轮反复选中，持续消耗第三方配额。
     */
    private void touchLastTrackTime(Shipment shipment) {
        shipment.setLastTrackTime(LocalDateTime.now());
        shipmentMapper.updateById(shipment);
    }

    /** 解析 ETA 字符串；仅接受 yyyy-MM-dd，其余返回 null（无法判定即不判延误，避免误伤） */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed, DATE_FMT);
        } catch (Exception e) {
            log.debug("ETA 格式无法解析，跳过延误判定：eta={}", value);
            return null;
        }
    }
}
