package com.amz.scheduler;

import com.amz.client.ReportsClient;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredentialStore;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.ShopPerformanceMapper;
import com.amz.service.ShopPerformanceSyncHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 店铺绩效定时留痕。
 *
 * <h2>§S33.33：此前有完整实现、有落库表，却没有任何调度</h2>
 * {@code PerformanceController} 提供了同步与查询端点，{@code ShopPerformanceSyncHandler}
 * 也实现了「拉报表 → 原文入库」，但**没有任何定时任务调用它**，前端也没有页面。
 * 结果是一张永远为空的表：`GET /spapi/performance/list/{shopId}` 只会返回空数组，
 * 而运维无法从「空」判断是「还没同步过」还是「店铺绩效一直正常」。
 *
 * <h2>为什么默认关闭</h2>
 * 与 {@code SettlementSyncScheduler} 同一条取舍：未配置凭证的环境不该反复报错。
 * 开启后按已登记凭证的店铺逐店同步，单店失败不影响其他店铺（下次调度重试，幂等）。
 *
 * <h2>为什么只做留痕、不解析字段</h2>
 * 本仓官方快照里没有 {@code GET_V2_SELLER_PERFORMANCE_REPORT} 的列结构
 * （见 {@code V12__shop_performance.sql} 的说明）。编造字段级解析器比不做更糟：
 * 运营会按错误的 ODR / 迟发率做决策。原文完整入库，等真实样本后再补解析，
 * 届时新增列即可，原文不丢。
 */
@Slf4j
@Component
public class ShopPerformanceSyncScheduler {

    /** 系统账号 ID（调度任务发起方，非真实用户）。 */
    private static final int SYSTEM_USER_ID = 0;

    /** 单店铺同步任务持锁时长（秒）。 */
    private static final long LOCK_LEASE_SECONDS = 900L;

    @Autowired
    private ReportsClient reportsClient;

    @Autowired
    private ShopPerformanceMapper performanceMapper;

    @Autowired
    private ShopCredentialStore credentialStore;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /** 是否启用（默认关闭：未配置凭证的环境不应反复报错）。 */
    @Value("${amz.spapi.performance.sync-enabled:false}")
    private boolean syncEnabled = false;

    /** 回看窗口（天）。绩效报表按窗口出数，重叠是安全的（每次都是一条新留痕）。 */
    @Value("${amz.spapi.performance.window-days:30}")
    private int windowDays = 30;

    @Value("${amz.spapi.performance.max-poll-attempts:15}")
    private int maxPollAttempts = 15;

    @Value("${amz.spapi.performance.poll-interval-ms:2000}")
    private long pollIntervalMs = 2000;

    @Value("${amz.spapi.performance.poll-deadline-ms:90000}")
    private long pollDeadlineMs = 90000;

    /**
     * 每日留痕（默认 05:10，错开结算同步 03:30 与订单同步高峰）。
     */
    @Scheduled(cron = "${amz.spapi.performance.sync-cron:0 10 5 * * ?}")
    public void syncDaily() {
        if (!syncEnabled) {
            log.info("shop performance sync disabled (amz.spapi.performance.sync-enabled=false), skip");
            return;
        }
        Set<Long> shopIds = credentialStore.getActiveShopIds();
        if (shopIds == null || shopIds.isEmpty()) {
            log.info("shop performance sync: no active shop credential, skip");
            return;
        }
        List<Long> ordered = shopIds.stream().sorted().toList();
        log.info("shop performance sync 启动：{} 个店铺，窗口 {} 天", ordered.size(), windowDays);
        int ok = 0;
        int failed = 0;
        for (Long shopId : ordered) {
            // 锁没抢到（其他实例正在同步同一店铺）返回 fallback=false，计为「跳过」
            Boolean success = distributedJobLock.runWithLock(
                    "spapi:performance-sync:" + shopId, LOCK_LEASE_SECONDS,
                    () -> syncOneShop(shopId), Boolean.FALSE);
            if (Boolean.TRUE.equals(success)) {
                ok++;
            } else {
                failed++;
            }
        }
        log.info("shop performance sync 完成：成功 {} 个，失败/跳过 {} 个", ok, failed);
    }

    /**
     * 同步单个店铺。返回 false 表示这家店没成功（已记日志），不影响其他店铺。
     */
    private boolean syncOneShop(Long shopId) {
        UserContext.setUserId(SYSTEM_USER_ID);
        UserContext.setShopId(shopId);
        UserContext.setShops(List.of(shopId));
        try {
            // marketplaceId 传 null：由 spapi 侧按店铺凭证登记的 marketplaceId 解析，
            // 避免在这里重复维护一份店铺→站点映射（与结算同步同一处理）
            ShopPerformanceSyncHandler handler = new ShopPerformanceSyncHandler(
                    reportsClient, performanceMapper,
                    maxPollAttempts, pollIntervalMs, pollDeadlineMs);
            var saved = handler.syncShop(shopId, null, windowDays);
            log.info("shop performance synced shopId={} reportId={} status={} contentLength={}",
                    shopId, saved.getReportId(), saved.getStatus(), saved.getContentLength());
            return true;
        } catch (Exception e) {
            // 单店失败不阻断其余店铺；下次调度会重试（每次写一条新留痕，天然幂等）
            log.error("shop performance sync failed shopId={}", shopId, e);
            return false;
        } finally {
            UserContext.clear();
        }
    }
}
