package com.amz.scheduler;

import com.amz.context.UserContext;
import com.amz.dto.SettlementIngestReport;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.service.PaymentCollectionService;
import com.amz.service.SettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结算原表每日增量同步（T04）。
 * <p>
 * 两个必须说明的设计点：
 * <ol>
 *   <li><b>调度线程没有用户上下文</b>。跨服务 Feign 调用若不带身份，下游
 *       {@code @ShopScoped} 与鉴权拦截器会拒绝（除非命中白名单）。
 *       按 {@code FeignAuthRelayConfig} 的约定，此处显式填充 {@link UserContext}
 *       （userId = 系统账号、shops = 本次同步的单个店铺），使下游租户校验链贯通。</li>
 *   <li><b>分布式防重</b>。多实例部署时同一店铺的同步必须只跑一次，
 *       用 {@link DistributedJobLock} 包住单店铺任务（抢不到锁即跳过，不排队）。</li>
 * </ol>
 * 幂等由结算表的业务指纹唯一索引兜底 —— 即使窗口重叠、重复拉取，也不会重复入账。
 */
@Slf4j
@Component
public class SettlementSyncScheduler {

    /** 系统账号 ID（调度任务发起方，非真实用户）。 */
    private static final int SYSTEM_USER_ID = 0;

    /** 单店铺同步任务持锁时长（秒）。 */
    private static final long LOCK_LEASE_SECONDS = 900L;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private PaymentCollectionService paymentCollectionService;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /** 是否启用自动同步（默认关闭 —— 未配置凭证的环境不应反复报错）。 */
    @Value("${amz.finance.settlement.sync-enabled:false}")
    private boolean syncEnabled = false;

    /** 额外配置的店铺 ID（逗号分隔），与已有结算数据的店铺取并集。 */
    @Value("${amz.finance.settlement.sync-shop-ids:}")
    private String configuredShopIds = "";

    /** 同步时间窗口（天）。窗口重叠是安全的（靠指纹去重），留余量以覆盖平台延迟入账。 */
    @Value("${amz.finance.settlement.sync-window-days:7}")
    private int syncWindowDays = 7;

    /**
     * 每日增量同步（默认 03:30）。
     */
    @Scheduled(cron = "${amz.finance.settlement.sync-cron:0 30 3 * * ?}")
    public void syncDaily() {
        if (!syncEnabled) {
            log.info("settlement sync disabled (amz.finance.settlement.sync-enabled=false), skip");
            return;
        }
        List<Long> shopIds = resolveShopIds();
        if (shopIds.isEmpty()) {
            log.info("settlement sync: no shop to sync (无已配置店铺且本地无结算数据)");
            return;
        }
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = end.minusDays(syncWindowDays);
        String dataStartTime = start.toString();
        String dataEndTime = end.toString();

        for (Long shopId : shopIds) {
            // 抢不到锁（其他实例正在同步同一店铺）直接跳过，不排队堆积
            distributedJobLock.runWithLock("finance:settlement-sync:" + shopId, LOCK_LEASE_SECONDS,
                    () -> syncOneShop(shopId, dataStartTime, dataEndTime), null);
        }
    }

    /**
     * 同步单个店铺：拉结算 → 落库 → 重算回款台账。
     */
    private Void syncOneShop(Long shopId, String dataStartTime, String dataEndTime) {
        UserContext.setUserId(SYSTEM_USER_ID);
        UserContext.setShopId(shopId);
        UserContext.setShops(List.of(shopId));
        try {
            // marketplaceId 传 null：由 spapi 侧按店铺凭证登记的 marketplaceId 解析区域，
            // 避免在 finance 侧重复维护一份店铺→站点映射
            SettlementIngestReport report = settlementService.sync(
                    shopId, null, dataStartTime, dataEndTime);
            log.info("settlement sync shopId={} reportId={} inserted={} skipped={} failed={}",
                    shopId, report.getReportId(), report.getInserted(),
                    report.getSkipped(), report.getFailed());
            if (!report.getWarnings().isEmpty()) {
                log.warn("settlement sync warnings shopId={} {}", shopId, report.getWarnings());
            }
            int orders = paymentCollectionService.rebuild(shopId);
            log.info("payment collection rebuilt shopId={} orders={}", shopId, orders);
        } catch (Exception e) {
            // 单店铺失败不影响其他店铺；下次调度会重试（幂等）
            log.error("settlement sync failed shopId={}", shopId, e);
        } finally {
            UserContext.clear();
        }
        return null;
    }

    /**
     * 待同步店铺 = 配置项 ∪ 已有结算数据的店铺。
     */
    private List<Long> resolveShopIds() {
        Set<Long> ids = new LinkedHashSet<>();
        if (configuredShopIds != null && !configuredShopIds.isBlank()) {
            for (String part : configuredShopIds.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        ids.add(Long.parseLong(trimmed));
                    } catch (NumberFormatException e) {
                        log.warn("invalid shop id in amz.finance.settlement.sync-shop-ids: {}", trimmed);
                    }
                }
            }
        }
        List<Long> fromData = settlementDetailMapper.selectDistinctShopIds();
        if (fromData != null) {
            ids.addAll(fromData);
        }
        return new ArrayList<>(ids);
    }
}
