package com.amz.scheduler;

import com.amz.client.LogisticsTrackingClient;
import com.amz.dto.IngestOutcome;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.ShipmentMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.service.TrackingIngestService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 物流轨迹自动同步调度。
 * <p>
 * <b>解决的业务问题：</b>改造前轨迹只能在货件详情页上逐条点「同步」触发，
 * 运营必须自己记住「哪些在途货件该去看一眼了」——这正是要消除的人工检索成本。
 * 本调度按固定周期自动拉取在途货件轨迹，把「人去查物流」变成「异常主动浮现」。
 * <p>
 * <b>三条准入规则，避免自动同步变成配额黑洞：</b>
 * <ol>
 *   <li><b>只处理非终态</b>：已签收入库（RECEIVED/CLOSED）的货件不再变化，
 *       继续拉取纯属浪费；</li>
 *   <li><b>跳过 IMPORT 偏好的货件</b>：这类货件由外部导入维护数据，
 *       调度主动拉取既消耗配额，又可能与使用者的数据口径打架；
 *       这是双入口并存的必要隔离——两条入口各管各的货件，互不打扰；</li>
 *   <li><b>按最近取数时间升序轮换 + 单轮限量</b>：保证限量下不会固定饿死尾部货件。</li>
 * </ol>
 * <p>
 * <b>分布式互斥：</b>多实例部署时若不加锁，每个实例都会拉一遍，
 * 配额消耗与告警量按实例数翻倍。锁租期需小于调度间隔，实例崩溃后下一轮可正常接手。
 */
@Slf4j
@Component
public class LogisticsTrackingSyncScheduler {

    /**
     * 头程跟踪已结束的状态：不再拉取承运商轨迹。
     * <p>
     * {@code DELIVERED} 也算结束——承运商确认送达后头程链路即完成，
     * 之后的「FBA 是否签收入库」由 FBA 入库渠道提供，承运商轨迹不再有新信息。
     * 不把它排除的话，已送达货件会被永久轮询，白耗第三方配额。
     * <p>
     * 注意 {@code DELAYED / EXCEPTION} <b>不</b>在此列：它们是「需要继续盯」的状态，
     * 正是最需要持续拉取以观察是否恢复的货件。
     */
    private static final Set<String> TRACKING_DONE_STATUSES = Set.of("DELIVERED", "RECEIVED", "CLOSED");

    /** 由外部导入维护的货件，调度不主动拉取 */
    private static final String DATA_SOURCE_IMPORT = "IMPORT";

    private static final String LOCK_TRACKING_SYNC = "amz:sched:logistics-tracking-sync";

    private static final String LOCK_DELAY_CHECK = "amz:sched:logistics-delay-check";

    @Autowired
    private ShipmentMapper shipmentMapper;

    @Autowired
    private LogisticsTrackingClient trackingClient;

    @Autowired
    private TrackingIngestService trackingIngestService;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /** 调度间隔（毫秒），默认 30 分钟 */
    @Value("${amz.logistics.sync.interval-ms:1800000}")
    private long syncIntervalMs;

    /** 锁租期（秒），须略小于调度间隔，默认 28 分钟 */
    @Value("${amz.logistics.sync.lease-seconds:1680}")
    private long syncLeaseSeconds;

    /**
     * 单轮最多处理的货件数，默认 200。
     * <p>
     * 限量是为了把单轮耗时与配额消耗钉在可预期范围内；
     * 配合按 last_track_time 升序的轮换，多余货件会在后续轮次自然被处理，不会饿死。
     */
    @Value("${amz.logistics.sync.max-per-round:200}")
    private int maxPerRound;

    @Value("${amz.logistics.delay-check.lease-seconds:1800}")
    private long delayLeaseSeconds;

    /** 延误判定宽限天数：ETA 超过该天数仍未推进才判延误，默认 0（ETA 当天即不再放宽） */
    @Value("${amz.logistics.delay-check.grace-days:0}")
    private int delayGraceDays;

    /**
     * 周期性拉取在途货件轨迹。
     * <p>
     * 用 fixedDelay 而非 cron：以「上一轮结束」为起点计时，避免上一轮因对端慢而拖长时
     * 下一轮立即叠加触发，造成同一批货件被并发拉取。
     */
    @Scheduled(fixedDelayString = "${amz.logistics.sync.interval-ms:1800000}")
    public void syncTracking() {
        distributedJobLock.runWithLock(LOCK_TRACKING_SYNC, syncLeaseSeconds, this::doSyncTracking);
    }

    /**
     * 周期性重判延误。
     * <p>
     * 刻意与轨迹拉取解耦：延误的成因是「时间流逝」而非「收到新轨迹」——
     * 一条已过 ETA 却再没有任何新轨迹的货件，恰恰是最需要被标出来的（卡住了），
     * 若只在收到新事件时判定，它会被永远漏掉。
     */
    @Scheduled(cron = "${amz.logistics.delay-check.cron:0 0 1 * * ?}")
    public void markDelayed() {
        distributedJobLock.runWithLock(LOCK_DELAY_CHECK, delayLeaseSeconds, () -> {
            int marked = trackingIngestService.markDelayedShipments(delayGraceDays);
            if (marked > 0) {
                log.info("延误重判完成：新标记延误货件 {} 个（宽限 {} 天）", marked, delayGraceDays);
            }
        });
    }

    // ------------------------------------------------------------------ 内部实现

    private void doSyncTracking() {
        // 未配置凭证时直接返回，不逐单打日志：否则每轮会为每个货件刷一条 warn，
        // 既无产出又会把真正的异常日志淹掉
        if (!trackingClient.isAvailable()) {
            log.info("物流轨迹自动同步已跳过：第三方轨迹客户端不可用（未开启或缺少凭证）。"
                    + "轨迹仍可通过 /logistics/import/* 由外部导入维护");
            return;
        }

        List<Shipment> candidates = loadCandidates();
        if (candidates.isEmpty()) {
            log.info("物流轨迹自动同步：无待同步货件（均为终态、缺运单号或已交由导入维护）");
            return;
        }

        int queried = 0;
        int accepted = 0;
        int statusChanged = 0;
        int failed = 0;

        for (Shipment shipment : candidates) {
            try {
                List<TrackingEvent> events = trackingClient.queryTracking(
                        shipment.getMasterTrackingNo(), shipment.getCarrier());
                IngestOutcome outcome = trackingIngestService.ingest(
                        shipment.getShipmentNo(), shipment.getMasterTrackingNo(),
                        events, TrackingIngestService.SOURCE_API, shipment.getShopId());
                queried++;
                if (!outcome.isMatched()) {
                    // 传给落库核心的货件编号取自库中记录，理论上必然匹配；
                    // 走到这里说明匹配逻辑与数据不一致，需要显式暴露而非静默跳过
                    log.warn("定时同步未能匹配回货件，请检查匹配逻辑：shipmentId={} shipmentNo={}",
                            shipment.getId(), shipment.getShipmentNo());
                    continue;
                }
                accepted += outcome.getAccepted();
                if (outcome.isStatusChanged()) {
                    statusChanged++;
                }
            } catch (Exception e) {
                // 单个货件失败不中断整轮：配额与耗时已经花出去，
                // 因一条数据问题放弃其余货件的拉取结果不划算
                failed++;
                log.error("定时同步单个货件失败，已跳过继续处理其余货件：shipmentId={} trackingNo={} err={}",
                        shipment.getId(), shipment.getMasterTrackingNo(), e.getMessage());
            }
        }

        log.info("物流轨迹自动同步完成：候选={} 已查询={} 新增轨迹点={} 状态变更={} 失败={}（间隔 {}ms）",
                candidates.size(), queried, accepted, statusChanged, failed, syncIntervalMs);
    }

    /**
     * 选取本轮待同步货件。
     * <p>
     * 排序用 {@code last_track_time ASC}：MySQL 中 NULL 排在最前，
     * 因此「从未同步过」的货件优先，其后按最久未同步依次处理。
     * <p>
     * <b>必须在 SQL 层排除无运单号的货件</b>：它们参与排序会永远占据队首
     * （取数时间无从刷新），导致真正可查询的货件排队靠后，单轮限量时被系统性饿死。
     */
    private List<Shipment> loadCandidates() {
        int limit = Math.max(1, maxPerRound);
        return shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                .notIn(Shipment::getStatus, TRACKING_DONE_STATUSES)
                .isNotNull(Shipment::getMasterTrackingNo)
                .ne(Shipment::getMasterTrackingNo, "")
                .and(w -> w.isNull(Shipment::getDataSource).or().ne(Shipment::getDataSource, DATA_SOURCE_IMPORT))
                .orderByAsc(Shipment::getLastTrackTime)
                .last("LIMIT " + limit));
    }
}
