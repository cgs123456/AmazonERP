package com.amz.service.impl;

import com.amz.client.LogisticsTrackingClient;
import com.amz.dto.CarrierPerformanceDTO;
import com.amz.dto.DashboardOverview;
import com.amz.dto.ShipmentAlertDTO;
import com.amz.dto.ShipmentDeliveredTime;
import com.amz.dto.TrendPointDTO;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.service.LogisticsDashboardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物流看板聚合实现。
 * <p>
 * <b>聚合方式的取舍：</b>采用「按店铺（及时间窗）一次性取出货件 + 内存聚合」，
 * 而不是为每个指标写一条 SQL 聚合。原因是本看板要一次算出十余个指标，
 * 若逐个下推数据库就是十余次往返，且口径分散在 SQL 里难以复用与测试；
 * 单店铺货件量级（千级以内）下内存聚合完全够用。
 * 唯一的例外是「送达事件时间」——它跨表且需要 MAX 分组，若在内存里做会退化成 N+1，
 * 因此下推为一次批量查询（见 {@link TrackingEventMapper#selectDeliveredTimes}）。
 * <p>
 * <b>若日后单店货件量级上升</b>（数万以上），应把 {@link #overview} 的状态计数与
 * {@link #trend} 的分桶改为 SQL GROUP BY；本类的对外契约不变，替换实现即可。
 */
@Slf4j
@Service
public class LogisticsDashboardServiceImpl implements LogisticsDashboardService {

    /** 货件全量合法状态，用于状态计数补齐，保证前端渲染不必做缺省兜底 */
    private static final List<String> ALL_STATUSES = List.of(
            "CREATED", "IN_TRANSIT", "CUSTOMS", "DELIVERED", "RECEIVED", "CLOSED", "DELAYED", "EXCEPTION");

    /** 头程跟踪已结束的状态：不参与在途/延误/告警统计 */
    private static final Set<String> TRACKING_DONE = Set.of("DELIVERED", "RECEIVED", "CLOSED");

    private static final Set<String> DATA_SOURCES = Set.of("IMPORT", "API", "AUTO");

    /** 承运商缺失时的分组名，避免前端出现无标题分组 */
    private static final String CARRIER_UNKNOWN = "未填写";

    private static final String STATUS_DELAYED = "DELAYED";

    private static final String STATUS_EXCEPTION = "EXCEPTION";

    private static final String SEVERITY_HIGH = "HIGH";
    private static final String SEVERITY_MEDIUM = "MEDIUM";
    private static final String SEVERITY_LOW = "LOW";

    private static final DateTimeFormatter CANONICAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 批量查询送达时间的单批上限，避免 IN 子句过长超出数据库参数上限 */
    private static final int DELIVERED_TIME_BATCH = 500;

    /** 告警清单返回上限：看板是「待办清单」而非全量导出，超出部分应由筛选条件缩小范围 */
    private static final int ALERT_LIMIT = 200;

    /** 趋势统计的合理天数区间 */
    private static final int TREND_MIN_DAYS = 7;
    private static final int TREND_MAX_DAYS = 180;

    @Autowired
    private ShipmentMapper shipmentMapper;

    @Autowired
    private TrackingEventMapper trackingEventMapper;

    @Autowired
    private LogisticsTrackingClient trackingClient;

    /** 取数过期阈值（小时）：在途货件超过该时长没有取数记录即提示「同步可能未覆盖」 */
    @Value("${amz.logistics.dashboard.stale-threshold-hours:48}")
    private int staleThresholdHours;

    /** 平均时效的统计窗口（天）：只统计窗口内送达的货件，避免历史久远的样本拉平近期表现 */
    @Value("${amz.logistics.dashboard.transit-window-days:90}")
    private int transitWindowDays;

    // ================================================================ 概览

    @Override
    public DashboardOverview overview(Long shopId) {
        DashboardOverview vo = new DashboardOverview();
        vo.setStaleThresholdHours(staleThresholdHours);
        vo.setTransitStatWindowDays(transitWindowDays);
        vo.setAutoSyncAvailable(trackingClient != null && trackingClient.isAvailable());

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (String status : ALL_STATUSES) {
            statusCounts.put(status, 0);
        }
        vo.setStatusCounts(statusCounts);

        Map<String, Integer> dataSourceCounts = new LinkedHashMap<>();
        for (String source : DATA_SOURCES) {
            dataSourceCounts.put(source, 0);
        }
        vo.setDataSourceCounts(dataSourceCounts);

        List<Shipment> shipments = loadByShop(shopId);
        vo.setTotalShipments(shipments.size());
        if (shipments.isEmpty()) {
            return vo;
        }

        LocalDate today = LocalDate.now();
        LocalDate arrivingDeadline = today.plusDays(7);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(Math.max(1, staleThresholdHours));

        int active = 0;
        int arrivingIn7Days = 0;
        int etaOverdue = 0;
        int missingTrackingNo = 0;
        int stale = 0;
        LocalDateTime lastTrackTime = null;
        List<Shipment> deliveredShipments = new ArrayList<>();

        for (Shipment shipment : shipments) {
            String status = normalizeStatus(shipment.getStatus());
            // 用 merge 而非 put：出现未登记的状态值时新增键而不是丢弃，避免统计漏计
            statusCounts.merge(status, 1, Integer::sum);
            dataSourceCounts.merge(normalizeDataSource(shipment.getDataSource()), 1, Integer::sum);

            if (shipment.getLastTrackTime() != null
                    && (lastTrackTime == null || shipment.getLastTrackTime().isAfter(lastTrackTime))) {
                lastTrackTime = shipment.getLastTrackTime();
            }

            if (TRACKING_DONE.contains(status)) {
                deliveredShipments.add(shipment);
                continue;
            }

            active++;
            if (isBlank(shipment.getMasterTrackingNo())) {
                missingTrackingNo++;
            } else if (shipment.getLastTrackTime() == null
                    || shipment.getLastTrackTime().isBefore(staleBefore)) {
                // 只在有运单号时才判「取数过期」：无运单号本就不可能取数，
                // 两类问题混报会让使用者分不清该补数据还是该查同步
                stale++;
            }

            LocalDate eta = parseDate(shipment.getEta());
            if (eta == null) {
                continue;
            }
            if (eta.isBefore(today)) {
                if (!STATUS_DELAYED.equals(status)) {
                    etaOverdue++;
                }
            } else if (!eta.isAfter(arrivingDeadline)) {
                arrivingIn7Days++;
            }
        }

        vo.setActiveShipments(active);
        vo.setDelayed(statusCounts.getOrDefault(STATUS_DELAYED, 0));
        vo.setException(statusCounts.getOrDefault(STATUS_EXCEPTION, 0));
        vo.setArrivingIn7Days(arrivingIn7Days);
        vo.setEtaOverdue(etaOverdue);
        vo.setMissingTrackingNo(missingTrackingNo);
        vo.setStaleShipments(stale);
        vo.setLastTrackTime(lastTrackTime);

        Map<Long, String> deliveredTimes = loadDeliveredTimes(deliveredShipments);
        vo.setAvgTransitDays(avgTransitDays(deliveredShipments, deliveredTimes));

        log.debug("看板概览聚合完成：shopId={} 货件={} 在途={} 延误={} 异常={}",
                shopId, shipments.size(), active, vo.getDelayed(), vo.getException());
        return vo;
    }

    // ================================================================ 趋势

    @Override
    public List<TrendPointDTO> trend(Long shopId, int days) {
        int window = Math.min(TREND_MAX_DAYS, Math.max(TREND_MIN_DAYS, days));
        LocalDate from = LocalDate.now().minusDays(window - 1L);
        LocalDateTime fromTime = from.atStartOfDay();
        LocalDate today = LocalDate.now();

        Map<LocalDate, int[]> buckets = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            buckets.put(from.plusDays(i), new int[2]);
        }

        // 建单量：按创建时间落窗
        List<Shipment> createdInWindow = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                .eq(Shipment::getShopId, shopId)
                .ge(Shipment::getCreateTime, fromTime));
        for (Shipment shipment : createdInWindow) {
            if (shipment.getCreateTime() == null) {
                continue;
            }
            int[] bucket = buckets.get(shipment.getCreateTime().toLocalDate());
            if (bucket != null) {
                bucket[0]++;
            }
        }

        // 送达量：按送达事件时间落窗。注意样本必须取全店已送达货件而不是上面那批——
        // 窗口内送达的货件很可能在窗口之前就建单了，只查窗口内新建的会漏掉大部分
        List<Shipment> deliveredShipments = new ArrayList<>();
        for (Shipment shipment : loadByShop(shopId)) {
            if (TRACKING_DONE.contains(normalizeStatus(shipment.getStatus()))) {
                deliveredShipments.add(shipment);
            }
        }
        for (String deliveredTime : loadDeliveredTimes(deliveredShipments).values()) {
            LocalDateTime time = parseDateTime(deliveredTime);
            if (time == null) {
                continue;
            }
            LocalDate date = time.toLocalDate();
            if (date.isBefore(from) || date.isAfter(today)) {
                continue;
            }
            int[] bucket = buckets.get(date);
            if (bucket != null) {
                bucket[1]++;
            }
        }

        List<TrendPointDTO> points = new ArrayList<>(buckets.size());
        buckets.forEach((date, counts) -> {
            TrendPointDTO point = new TrendPointDTO();
            point.setDate(date.format(DATE_FMT));
            point.setCreated(counts[0]);
            point.setDelivered(counts[1]);
            points.add(point);
        });
        return points;
    }

    // ================================================================ 承运商表现

    @Override
    public List<CarrierPerformanceDTO> carrierPerformance(Long shopId) {
        List<Shipment> shipments = loadByShop(shopId);
        if (shipments.isEmpty()) {
            return List.of();
        }

        Map<String, List<Shipment>> byCarrier = new LinkedHashMap<>();
        for (Shipment shipment : shipments) {
            String carrier = isBlank(shipment.getCarrier()) ? CARRIER_UNKNOWN : shipment.getCarrier().trim();
            byCarrier.computeIfAbsent(carrier, k -> new ArrayList<>()).add(shipment);
        }

        List<Shipment> deliveredShipments = new ArrayList<>();
        for (Shipment shipment : shipments) {
            if (TRACKING_DONE.contains(normalizeStatus(shipment.getStatus()))) {
                deliveredShipments.add(shipment);
            }
        }
        Map<Long, String> deliveredTimes = loadDeliveredTimes(deliveredShipments);

        List<CarrierPerformanceDTO> result = new ArrayList<>(byCarrier.size());
        byCarrier.forEach((carrier, group) -> result.add(buildCarrierPerformance(carrier, group, deliveredTimes)));
        // 按时效不可比的样本量降序：样本太少的分组排后面，避免小样本拉偏结论
        result.sort(Comparator.comparingInt(CarrierPerformanceDTO::getTotal).reversed());
        return result;
    }

    private CarrierPerformanceDTO buildCarrierPerformance(String carrier, List<Shipment> group,
                                                          Map<Long, String> deliveredTimes) {
        CarrierPerformanceDTO vo = new CarrierPerformanceDTO();
        vo.setCarrier(carrier);
        vo.setTotal(group.size());

        int active = 0;
        int delivered = 0;
        int delayed = 0;
        int exception = 0;
        List<Shipment> deliveredGroup = new ArrayList<>();

        for (Shipment shipment : group) {
            String status = normalizeStatus(shipment.getStatus());
            if (TRACKING_DONE.contains(status)) {
                delivered++;
                deliveredGroup.add(shipment);
            } else {
                active++;
            }
            if (STATUS_DELAYED.equals(status)) {
                delayed++;
            }
            if (STATUS_EXCEPTION.equals(status)) {
                exception++;
            }
        }

        vo.setActive(active);
        vo.setDelivered(delivered);
        vo.setDelayed(delayed);
        vo.setException(exception);
        vo.setAvgTransitDays(avgTransitDays(deliveredGroup, deliveredTimes));
        vo.setDelayedRate(rate(delayed, group.size()));
        vo.setExceptionRate(rate(exception, group.size()));
        return vo;
    }

    // ================================================================ 告警清单

    @Override
    public List<ShipmentAlertDTO> alerts(Long shopId) {
        List<Shipment> shipments = loadByShop(shopId);
        if (shipments.isEmpty()) {
            return List.of();
        }

        LocalDate today = LocalDate.now();
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(Math.max(1, staleThresholdHours));
        List<ShipmentAlertDTO> alerts = new ArrayList<>();

        for (Shipment shipment : shipments) {
            String status = normalizeStatus(shipment.getStatus());
            if (TRACKING_DONE.contains(status)) {
                continue;
            }
            LocalDate eta = parseDate(shipment.getEta());
            Integer daysOverdue = eta == null ? null : (int) (today.toEpochDay() - eta.toEpochDay());

            if (STATUS_DELAYED.equals(status)) {
                alerts.add(alert(shipment, "DELAYED", SEVERITY_HIGH, daysOverdue,
                        eta == null ? "已判定延误，未填写 ETA 无法显示超期天数"
                                : "预计到港 " + eta + "，已超期 " + daysOverdue + " 天仍未推进",
                        "联系承运商核实实际位置，必要时调整补货计划"));
                continue;
            }
            if (STATUS_EXCEPTION.equals(status)) {
                alerts.add(alert(shipment, "EXCEPTION", SEVERITY_HIGH, daysOverdue,
                        "轨迹出现异常（扣关 / 查验 / 退件等）",
                        "核实清关或退回处理进度，确认是否需要重新发运"));
                continue;
            }
            if (daysOverdue != null && daysOverdue > 0) {
                alerts.add(alert(shipment, "ETA_OVERDUE", SEVERITY_MEDIUM, daysOverdue,
                        "ETA 已过 " + daysOverdue + " 天但状态未标为延误（延误重判任务可能尚未执行）",
                        "手工触发一次同步确认最新轨迹"));
                continue;
            }
            if (isBlank(shipment.getMasterTrackingNo())) {
                alerts.add(alert(shipment, "MISSING_TRACKING_NO", SEVERITY_LOW, daysOverdue,
                        "缺少主运单号，无法自动获取轨迹",
                        "补录主运单号后即纳入自动跟踪"));
                continue;
            }
            if (shipment.getLastTrackTime() == null || shipment.getLastTrackTime().isBefore(staleBefore)) {
                long hours = shipment.getLastTrackTime() == null ? -1
                        : Duration.between(shipment.getLastTrackTime(), LocalDateTime.now()).toHours();
                alerts.add(alert(shipment, "STALE_DATA", SEVERITY_MEDIUM, daysOverdue,
                        hours < 0 ? "从未取到过轨迹数据，请检查运单号或取数来源配置"
                                : "已 " + hours + " 小时无取数记录，自动同步可能未覆盖该货件",
                        "检查取数来源配置，或改用外部导入补录轨迹"));
                continue;
            }
            if (daysOverdue != null && daysOverdue >= -3) {
                alerts.add(alert(shipment, "ETA_APPROACHING", SEVERITY_LOW, daysOverdue,
                        "预计 " + (-daysOverdue) + " 天后到港",
                        "关注清关与派送进度，提前准备 FBA 入库预约"));
            }
        }

        // 严重程度优先；同级内超期越久越靠前，让最该先处理的排在最上面
        alerts.sort(Comparator
                .comparingInt((ShipmentAlertDTO a) -> severityWeight(a.getSeverity()))
                .thenComparing(a -> a.getDaysOverdue() == null ? Integer.MIN_VALUE : -a.getDaysOverdue()));

        if (alerts.size() > ALERT_LIMIT) {
            log.info("看板告警数超上限已截断：shopId={} 实际={} 上限={}", shopId, alerts.size(), ALERT_LIMIT);
            return new ArrayList<>(alerts.subList(0, ALERT_LIMIT));
        }
        return alerts;
    }

    private ShipmentAlertDTO alert(Shipment shipment, String type, String severity, Integer daysOverdue,
                                   String message, String actionHint) {
        ShipmentAlertDTO vo = new ShipmentAlertDTO();
        vo.setType(type);
        vo.setSeverity(severity);
        vo.setShipmentId(shipment.getId());
        vo.setShipmentNo(shipment.getShipmentNo());
        vo.setCarrier(shipment.getCarrier());
        vo.setMasterTrackingNo(shipment.getMasterTrackingNo());
        vo.setStatus(normalizeStatus(shipment.getStatus()));
        vo.setEta(shipment.getEta());
        vo.setDaysOverdue(daysOverdue);
        vo.setLastTrackTime(shipment.getLastTrackTime());
        vo.setMessage(message);
        vo.setActionHint(actionHint);
        return vo;
    }

    private int severityWeight(String severity) {
        if (SEVERITY_HIGH.equals(severity)) {
            return 0;
        }
        if (SEVERITY_MEDIUM.equals(severity)) {
            return 1;
        }
        return 2;
    }

    // ================================================================ 内部工具

    private List<Shipment> loadByShop(Long shopId) {
        return shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                .eq(Shipment::getShopId, shopId));
    }

    /**
     * 批量取回这些货件的送达事件时间。
     * <p>
     * 分批而非一次全量：{@code IN} 子句的元素数与 SQL 包体都随货件数线性增长，
     * 单店货件多时会逼近数据库参数上限并拖慢解析。分批后单次开销可控。
     */
    private Map<Long, String> loadDeliveredTimes(List<Shipment> shipments) {
        Map<Long, String> result = new HashMap<>();
        if (shipments.isEmpty()) {
            return result;
        }
        List<Long> ids = new ArrayList<>(shipments.size());
        for (Shipment shipment : shipments) {
            if (shipment.getId() != null) {
                ids.add(shipment.getId());
            }
        }
        for (int start = 0; start < ids.size(); start += DELIVERED_TIME_BATCH) {
            List<Long> batch = ids.subList(start, Math.min(ids.size(), start + DELIVERED_TIME_BATCH));
            if (batch.isEmpty()) {
                continue;
            }
            List<ShipmentDeliveredTime> rows = trackingEventMapper.selectDeliveredTimes(batch);
            if (rows == null) {
                continue;
            }
            for (ShipmentDeliveredTime row : rows) {
                if (row.getShipmentId() != null) {
                    result.put(row.getShipmentId(), row.getDeliveredTime());
                }
            }
        }
        return result;
    }

    /**
     * 平均头程时效（天）：创建时间 → 承运商确认送达的事件时间。
     * <p>
     * 只统计 {@code transitWindowDays} 窗口内送达的样本，避免一年前的慢船拉平近期表现，
     * 使指标失去对「最近换承运商是否奏效」的敏感度。
     * <p>
     * 无样本时返回 null 而不是 0：0 会被读成「当天送达」，
     * 而实际含义是「没有可算的数据」，二者必须能区分。
     */
    private Double avgTransitDays(List<Shipment> shipments, Map<Long, String> deliveredTimes) {
        LocalDateTime windowStart = LocalDateTime.now().minusDays(Math.max(1, transitWindowDays));
        long totalSeconds = 0L;
        int samples = 0;

        for (Shipment shipment : shipments) {
            if (shipment.getId() == null || shipment.getCreateTime() == null) {
                continue;
            }
            String deliveredTime = deliveredTimes.get(shipment.getId());
            LocalDateTime delivered = parseDateTime(deliveredTime);
            if (delivered == null || delivered.isBefore(windowStart)) {
                continue;
            }
            long seconds = Duration.between(shipment.getCreateTime(), delivered).getSeconds();
            if (seconds < 0) {
                // 送达早于建单：数据本身有矛盾（常见于手工改动或导入错误），
                // 计入会拉低平均值并掩盖问题，故排除
                log.debug("送达时间早于创建时间，已排除出时效统计：shipmentId={}", shipment.getId());
                continue;
            }
            totalSeconds += seconds;
            samples++;
        }
        if (samples == 0) {
            return null;
        }
        return BigDecimal.valueOf(totalSeconds / 86400d / samples)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private BigDecimal rate(int part, int total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private String normalizeStatus(String status) {
        if (isBlank(status)) {
            return "CREATED";
        }
        return status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String normalizeDataSource(String dataSource) {
        if (isBlank(dataSource)) {
            // 数据库默认值为 AUTO；实体未显式赋值时读出来可能为 null，按 AUTO 归类
            return "AUTO";
        }
        String upper = dataSource.trim().toUpperCase(java.util.Locale.ROOT);
        return DATA_SOURCES.contains(upper) ? upper : "AUTO";
    }

    /** 解析 yyyy-MM-dd（容忍带时间的完整串，取日期部分）；失败返回 null */
    private LocalDate parseDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text, DATE_FMT);
        } catch (DateTimeParseException e) {
            log.debug("ETA 无法解析，跳过：{}", value);
            return null;
        }
    }

    /** 解析归一化后的时间串；失败返回 null（会导致该样本被跳过，不影响其他样本） */
    private LocalDateTime parseDateTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), CANONICAL_FMT);
        } catch (DateTimeParseException e) {
            log.debug("时间格式无法解析，跳过该样本：{}", value);
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
