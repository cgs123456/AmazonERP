package com.amz.scheduler;

import com.amz.lock.DistributedJobLock;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.model.PlatformAccount;
import com.amz.service.impl.MultiplatformServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多平台订单定时同步。
 * <p>
 * <b>为什么需要它</b>：{@code POST /multiplatform/sync/all/{shopId}} 早就存在且实现完备
 * （异常按平台隔离、单平台失败继续后续），但**全模块零 {@code @Scheduled}**——
 * 真实凭据到位后仍要人每天点按钮，等于没接入。SP-API 侧每个域都有调度 + 分布式锁，
 * 这里补齐同样的一层。
 * <p>
 * <b>默认关闭</b>（{@code amz.multiplatform.sync.enabled=false}）：与结算同步、
 * 店铺绩效同步、退货同步同一取舍——未配置平台凭据的环境不应反复报错。
 * <p>
 * <b>为什么不复用 {@code runIdempotentWithLock}</b>：该入口在 Redis 不可用时降级为
 * 直接执行，只允许幂等或只读任务使用。订单同步会调外部平台并对
 * {@code amz_unified_order} 写库——不是只读，故用严格版 {@code runWithLock}：
 * 拿不到锁就整轮跳过，宁可少跑一轮也不并发双写。
 */
@Slf4j
@Component
@Profile("!bootstrap")
public class MultiplatformOrderSyncScheduler {

    /** 平台标识：与 {@code MultiplatformServiceImpl.syncAllPlatforms} 的内置顺序一致。 */
    private static final List<String> PLATFORMS = List.of("TEMU", "TIKTOK", "SHEIN");

    /** 锁键前缀；租期略小于调度周期，实例崩溃后下一轮可正常接手。 */
    private static final String LOCK_PREFIX = "amz:sched:multiplatform-order-sync:";

    @Autowired
    private MultiplatformServiceImpl multiplatformService;

    @Autowired
    private PlatformAccountMapper platformAccountMapper;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /**
     * 是否启用自动同步。默认关闭——未配置平台凭据的环境不应反复报错。
     */
    @Value("${amz.multiplatform.sync.enabled:false}")
    private boolean syncEnabled = false;

    /**
     * 额外配置的店铺 ID（逗号分隔），与「已有 ACTIVE 平台账号的店铺」取并集。
     * <p>
     * 为什么允许显式配置：ACTIVE 账号是同步的充分条件但不是必要条件——
     * 运行中账号可能因鉴权失败被置为 ERROR，而运营仍希望它被重试。
     */
    @Value("${amz.multiplatform.sync.shop-ids:}")
    private String configuredShopIds = "";

    /**
     * 每 30 分钟执行一次（上一次执行结束后起算 fixedDelay）。
     * <p>
     * 周期比 SP-API 的 15 分钟长：多平台订单量级远小于 Amazon，且各平台限流更紧，
     * 更短的周期只会换来 429 而不是更及时的数据。
     */
    @Scheduled(fixedDelayString = "${amz.multiplatform.sync.interval-ms:1800000}")
    public void syncOrders() {
        if (!syncEnabled) {
            log.debug("multiplatform order sync disabled "
                    + "(amz.multiplatform.sync.enabled=false), skip");
            return;
        }
        // 严格锁：拿不到就整轮跳过。这是外部写操作，不允许降级为并发执行。
        distributedJobLock.runWithLock(
                LOCK_PREFIX + "all",
                25 * 60L,
                this::doSyncOrders,
                null);
    }

    /**
     * 执行一轮同步。包可见是为了测试能直接驱动，不必等调度触发。
     *
     * @return 本轮汇总（店铺数 / 成功 / 失败 / 入库行数）
     */
    SyncSummary doSyncOrders() {
        Set<Long> shopIds = resolveShopIds();
        if (shopIds.isEmpty()) {
            // 与「确实没有账号」区分开：这一行是运维判断「调度是否真的在工作」的依据
            log.info("multiplatform order sync: no shop with platform account, skipping");
            return new SyncSummary(0, 0, 0, 0);
        }

        int succeeded = 0;
        int failed = 0;
        int inserted = 0;
        for (Long shopId : shopIds) {
            try {
                MultiplatformServiceImpl.OrderSyncSummary summary =
                        multiplatformService.syncAllPlatforms(shopId);
                inserted += summary.inserted();
                // syncAllPlatforms 内部已按平台隔离失败；这里的 failed 是平台级，
                // 只记日志不中断——整店级异常由下面的 catch 计。
                if (summary.failed() > 0) {
                    log.warn("multiplatform order sync shopId={} 有 {} 个平台失败：{}",
                            shopId, summary.failed(), summary.failedPlatforms());
                }
                succeeded++;
            } catch (Exception e) {
                // 单店失败不影响其他店铺（与 SP-API 订单调度同一约定）
                failed++;
                log.error("multiplatform order sync failed shopId={}，跳过该店铺继续", shopId, e);
            }
        }
        log.info("multiplatform order sync done: shops={} succeeded={} failed={} inserted={}",
                shopIds.size(), succeeded, failed, inserted);
        return new SyncSummary(shopIds.size(), succeeded, failed, inserted);
    }

    /**
     * 解析待同步店铺：已有 ACTIVE 平台账号的店铺 ∪ 显式配置的店铺。
     * <p>
     * 用一次 {@code selectList} 取所有 ACTIVE 账号再内存去重，而不是按平台查三次——
     * 账号表通常很小（每店每平台一行），一次有界读比三次往返更省。
     */
    private Set<Long> resolveShopIds() {
        Set<Long> shopIds = new LinkedHashSet<>();
        try {
            List<PlatformAccount> accounts = platformAccountMapper.selectList(
                    new LambdaQueryWrapper<PlatformAccount>()
                            .select(PlatformAccount::getShopId)
                            .eq(PlatformAccount::getStatus, "ACTIVE")
                            .in(PlatformAccount::getPlatform, PLATFORMS));
            if (accounts != null) {
                for (PlatformAccount account : accounts) {
                    if (account.getShopId() != null) {
                        shopIds.add(account.getShopId());
                    }
                }
            }
        } catch (Exception e) {
            // 查询失败不等于「没有账号」：必须响亮失败，否则调度会静默空转
            throw new IllegalStateException("查询多平台 ACTIVE 账号失败", e);
        }

        for (String raw : configuredShopIds.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                shopIds.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                // 配置错误必须点名，否则运营改了配置却不生效且无从察觉
                log.warn("amz.multiplatform.sync.shop-ids 含无法解析的店铺 ID：「{}」，已忽略", trimmed);
            }
        }
        return shopIds;
    }

    /** 一轮同步的汇总。 */
    public record SyncSummary(int shops, int succeeded, int failed, int inserted) {
    }
}
