package com.amz.scheduler;

import com.amz.connector.SpApiEndpointResolver;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.lock.DistributedJobLock;
import com.amz.service.impl.SalesHistorySyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * {@code amz_sales_history} 定时同步。
 * <p>
 * <b>为什么必须有它</b>：{@link SalesHistorySyncService} 本身实现了完整的
 * 三段式取数（createReport → 轮询 → 下载解析 → upsert），但**没有任何调用方**——
 * 没有调度器、没有控制器、没有前端入口。这比「没写」更隐蔽：代码在、测试绿、
 * 类注释写着「写入方已补上」，而库里依然是空的，补货建议继续基于空历史出数。
 * 本次补的正是这条接线。
 * <p>
 * <b>默认关闭</b>（{@code spapi.sales-history.sync-enabled=false}）：与结算同步、
 * 绩效同步、退货同步同一取舍——未配置 SP-API 凭证的环境不该反复报错。
 * <p>
 * <b>为什么用 {@code runIdempotentWithLock} 而不是严格锁</b>：本任务唯一的写操作是
 * 按 {@code uk_shop_sku_date} 的 upsert，同一窗口重复执行得到同一结果（报表是当日全量，
 * {@code SalesHistoryMapper.upsertBatch} 覆盖而非累加）。因此 Redis 不可用时降级为直接执行
 * 不会造成数据错误，只是多消耗一次 SP-API 配额。这与订单同步（外部写 + 非幂等）
 * 必须用严格锁的取舍不同。
 * <p>
 * <b>P0-51 端点覆盖</b>：覆盖生效期间必须跳过——桩/联调数据不得写进业务表。
 * 与库存同步、订单同步同一纪律，且手动入口也要共用同一判定。
 */
@Slf4j
@Component
@Profile("!bootstrap")
public class SalesHistorySyncScheduler {

    /** 单店铺任务持锁时长（秒）。报表轮询挂钟上限 90s，留足余量。 */
    private static final long LOCK_LEASE_SECONDS = 600L;

    @Autowired
    private SalesHistorySyncService salesHistorySyncService;

    @Autowired
    private ShopCredentialStore credentialStore;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /**
     * P0-51：端点覆盖生效时禁止落库。
     * <p>
     * {@code @Autowired(required = false)}：部分测试切片不装配该 Bean，
     * 但生产环境它一定存在（{@code SpApiEndpointResolver} 是 {@code @Component}）。
     */
    @Autowired(required = false)
    private SpApiEndpointResolver spApiEndpointResolver;

    /** 是否启用（默认关闭：未配置凭证的环境不应反复报错）。 */
    @Value("${spapi.sales-history.sync-enabled:false}")
    private boolean syncEnabled = false;

    /** 回补窗口（天）。与 {@code ReplenishmentEngine} 的 30 天基线窗口同值。 */
    @Value("${spapi.sales-history.sync-days:30}")
    private int syncDays = 30;

    /**
     * 每日 04:20 同步（错开结算 03:30、退货 04:00、绩效 05:10、补货重算 06:00）。
     * <p>
     * 顺序是有意的：销量历史必须在补货重算（06:00）之前落地，否则当天算出的建议
     * 仍然基于昨天之前的窗口。
     */
    @Scheduled(cron = "${spapi.sales-history.sync-cron:0 20 4 * * ?}")
    public void syncDaily() {
        if (!syncEnabled) {
            log.debug("sales history sync disabled (spapi.sales-history.sync-enabled=false), skip");
            return;
        }
        if (skipBecauseEndpointOverride("salesHistorySync")) {
            return;
        }
        Set<Long> shopIds = credentialStore.getActiveShopIds();
        if (shopIds == null || shopIds.isEmpty()) {
            log.info("sales history sync: no active shop credential, skip");
            return;
        }
        List<Long> ordered = shopIds.stream().sorted().toList();
        log.info("sales history sync 启动：{} 个店铺，窗口 {} 天", ordered.size(), syncDays);

        int ok = 0;
        int failed = 0;
        int skippedNoMarketplace = 0;
        for (Long shopId : ordered) {
            // marketplaceId 从凭证读取而不是配置一个全局值：Amazon 报表按站点取数，
            // 多站点店铺用同一个 marketplaceId 会拉错店。缺 marketplaceId 的店铺
            // 显式跳过并计数——静默跳过会让「配置没填」看起来像「这家店没销量」。
            ShopCredential credential = credentialStore.get(shopId);
            String marketplaceId = credential == null ? null : credential.getMarketplaceId();
            if (marketplaceId == null || marketplaceId.isBlank()) {
                skippedNoMarketplace++;
                log.warn("sales history sync skip shopId={}：凭证缺少 marketplaceId", shopId);
                continue;
            }
            // 幂等锁：重复执行是安全的（upsert 覆盖），Redis 不可用时可降级
            Boolean success = distributedJobLock.runIdempotentWithLock(
                    "spapi:sales-history-sync:" + shopId, LOCK_LEASE_SECONDS,
                    () -> syncOneShop(shopId, marketplaceId), Boolean.FALSE);
            if (Boolean.TRUE.equals(success)) {
                ok++;
            } else {
                failed++;
            }
        }
        log.info("sales history sync 完成：成功 {} 个，失败/跳过 {} 个，缺站点 {} 个",
                ok, failed, skippedNoMarketplace);
    }

    /**
     * 同步单个店铺。返回 false 表示这家店没成功（已记日志），不影响其他店铺。
     */
    private boolean syncOneShop(Long shopId, String marketplaceId) {
        try {
            SalesHistorySyncService.SyncReport report =
                    salesHistorySyncService.sync(shopId, marketplaceId, syncDays);
            log.info("sales history synced shopId={} reportId={} status={} rows={} written={} warnings={}",
                    shopId, report.getReportId(), report.getReportStatus(),
                    report.getDataLineCount(), report.getWritten(), report.getWarnings().size());
            // 行级错误不当作整店失败（单行坏数据不该让其余 SKU 的销量全部不落库），
            // 但必须留痕：否则「报表格式变了」会表现成「销量莫名其妙变少」。
            if (!report.getRowErrors().isEmpty()) {
                log.warn("sales history shopId={} 有 {} 行解析失败，首条：{}",
                        shopId, report.getRowErrors().size(), report.getRowErrors().get(0));
            }
            return true;
        } catch (Exception e) {
            // 单店失败不阻断其余店铺；下次调度会重试（窗口重叠 + upsert 幂等）
            log.error("sales history sync failed shopId={}", shopId, e);
            return false;
        }
    }

    /**
     * P0-51：端点覆盖生效（非生产桩/联调）期间禁止落库。
     * 定时任务与手动触发共用同一判定，避免把桩数据写进业务表。
     */
    private boolean skipBecauseEndpointOverride(String caller) {
        if (spApiEndpointResolver == null || !spApiEndpointResolver.isOverrideActive()) {
            return false;
        }
        log.warn("{} skipped: {}={} 生效（仅限非生产的桩/联调地址），"
                        + "为避免桩数据污染业务表，本次不调用平台也不落库；恢复真实同步请清空该配置。",
                caller, SpApiEndpointResolver.OVERRIDE_PROPERTY, spApiEndpointResolver.baseUrlOverride());
        return true;
    }
}
