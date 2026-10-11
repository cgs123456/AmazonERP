package com.amz.scheduler;

import com.amz.context.UserContext;
import com.amz.lock.DistributedJobLock;
import com.amz.service.impl.AmazonReturnSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Amazon FBA 客户退货每日同步。
 * <p>
 * <b>默认关闭</b>（{@code amz.logistics.return-sync.enabled=false}）：未配置凭证的环境
 * 不应反复报错，与 {@code SettlementSyncScheduler} 同一约定。
 * <p>
 * <b>幂等</b>：导入按 {@code external_key} 去重，窗口重叠（常态）不会重复建单，
 * 因此失败只要不推进任何游标即可下次重试——本调度<b>不维护游标</b>，
 * 每次按固定窗口重取，靠幂等键收敛。
 * <p>
 * <b>FBM 不在此列</b>：SP-API 没有卖家自发货退货报表，FBM 退货只能手工登记。
 * 这里只同步 FBA。
 * <p>
 * 店铺来源：配置项 {@code amz.logistics.return-sync.shop-ids}（逗号分隔）。
 * 不自动扫描「有退货单的店铺」——自动同步会去调 Amazon，必须有明确的店铺清单授权，
 * 不能因为某店历史上有一条手工退货单就去拉它的报表。
 */
@Slf4j
@Component
public class AmazonReturnSyncScheduler {

    /** 系统调度身份：调度线程没有登录用户，但下游服务层归属校验需要一个 userId。 */
    private static final int SYSTEM_USER_ID = 0;

    private static final long LOCK_LEASE_SECONDS = 900L;

    @Autowired(required = false)
    private AmazonReturnSyncService amazonReturnSyncService;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /** 是否启用自动同步（默认关闭）。 */
    @Value("${amz.logistics.return-sync.enabled:false}")
    private boolean syncEnabled = false;

    /** 待同步店铺（逗号分隔）。为空则整轮跳过——不猜测要同步哪些店。 */
    @Value("${amz.logistics.return-sync.shop-ids:}")
    private String configuredShopIds = "";

    /** 站点（marketplaceId）。Amazon 报表按站点取数；单站点配置项，多站点需扩展为店铺→站点映射。 */
    @Value("${amz.logistics.return-sync.marketplace-id:ATVPDKIKX0DER}")
    private String marketplaceId = "ATVPDKIKX0DER";

    /** 回溯窗口（天）。窗口重叠安全（靠幂等键去重），留余量覆盖平台延迟出报表。 */
    @Value("${amz.logistics.return-sync.window-days:7}")
    private int windowDays = 7;

    /**
     * 每日同步（默认 04:00，错开结算同步的 03:30）。
     */
    @Scheduled(cron = "${amz.logistics.return-sync.cron:0 0 4 * * ?}")
    public void syncDaily() {
        if (!syncEnabled) {
            log.info("return sync disabled (amz.logistics.return-sync.enabled=false), skip");
            return;
        }
        if (amazonReturnSyncService == null) {
            log.warn("return sync enabled but AmazonReturnSyncService 未装配，skip");
            return;
        }
        List<Long> shopIds = resolveShopIds();
        if (shopIds.isEmpty()) {
            log.info("return sync: no shop configured (amz.logistics.return-sync.shop-ids 为空)");
            return;
        }
        for (Long shopId : shopIds) {
            // 抢不到锁（其他实例正在同步同一店铺）直接跳过，不排队堆积
            distributedJobLock.runWithLock("logistics:return-sync:" + shopId, LOCK_LEASE_SECONDS,
                    () -> syncOneShop(shopId), null);
        }
    }

    /**
     * 同步单店铺。单店失败不影响其他店铺，下次调度重试（幂等）。
     */
    private Void syncOneShop(Long shopId) {
        // 调度线程没有登录身份：显式填充，否则下游服务层归属校验会拒绝
        UserContext.setUserId(SYSTEM_USER_ID);
        UserContext.setShopId(shopId);
        UserContext.setShops(List.of(shopId));
        try {
            AmazonReturnSyncService.SyncResult result =
                    amazonReturnSyncService.syncShop(shopId, marketplaceId, windowDays);
            log.info("return sync shopId={} fetched={} imported={} skipped={} rowErrors={}",
                    shopId, result.fetched(), result.imported(), result.skipped(), result.rowErrors());
        } catch (Exception e) {
            log.error("return sync failed shopId={}", shopId, e);
        } finally {
            UserContext.clear();
        }
        return null;
    }

    private List<Long> resolveShopIds() {
        Set<Long> ids = new LinkedHashSet<>();
        if (configuredShopIds != null && !configuredShopIds.isBlank()) {
            for (String part : configuredShopIds.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        ids.add(Long.parseLong(trimmed));
                    } catch (NumberFormatException e) {
                        log.warn("invalid shop id in amz.logistics.return-sync.shop-ids: {}", trimmed);
                    }
                }
            }
        }
        return new ArrayList<>(ids);
    }
}
