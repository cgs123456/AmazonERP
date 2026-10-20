package com.amz.scheduler;

import com.amz.lock.DistributedJobLock;
import com.amz.mapper.CarrierQuoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 过期运价收口：把已过 {@code expiry_date} 但仍为 ACTIVE 的报价置为 EXPIRED。
 * <p>
 * <b>为什么需要它</b>：此前只有手动入口（{@code POST /quote/expire-outdated/{shopId}}），
 * 报价状态不会随时间自动翻转。比价时虽然已按日期强制过滤（不会用失效运价选商），
 * 但看板上的 ACTIVE 计数与「已失效未收口」列表会持续偏离事实，且无人工介入就一直偏离。
 * <p>
 * <b>店铺来源：不配白名单。</b> 与同模块的退货同步 / 结算同步不同——那两个任务要拿
 * 店铺身份去调 Amazon，必须有明确的店铺清单授权；本任务只做数据库状态位更新
 * （{@code expiry_date < today}），不读取业务数据、不发起任何外部请求，
 * 因此不存在越权访问他人店铺数据的问题。加白名单反而会制造
 * 「新增店铺忘了配而报价永不过期」的静默故障。
 * <p>
 * <b>为什么直接持有 Mapper 而不是走 Service</b>：这件事就是一条 UPDATE。
 * 走 {@code LogisticsUpgradeService} 会让那个本已 899 行（超 500 上限）的巨型类
 * 再长一截，而它并不提供任何本任务需要的业务规则。
 * 仓库的行数棘轮（{@code file_size_ratchet.py}）只允许超限文件变小，
 * 往里面加代码会被门禁挡下——这是该门禁的设计意图，不是障碍。
 * <p>
 * <b>为什么用 fixedDelay 而非 cron</b>：与 {@code LogisticsTrackingSyncScheduler} 同一理由，
 * 以「上一轮结束」计时，避免上一轮因大表更新慢而在下一轮叠加触发。
 * 默认间隔 6 小时——过期是「按天」变化的事实，不需要分钟级精度。
 */
@Slf4j
@Component
public class QuoteExpiryScheduler {

    private static final String LOCK_KEY = "logistics:quote-expiry";

    /** 锁租期：单条 UPDATE 的预期耗时远小于此，留足余量避免锁提前释放后并发重入。 */
    private static final long LOCK_LEASE_SECONDS = 300L;

    @Autowired
    private CarrierQuoteMapper carrierQuoteMapper;

    @Autowired
    private DistributedJobLock distributedJobLock;

    /** 是否启用（默认开启：纯 DB 操作，失败无外部副作用）。 */
    @Value("${amz.logistics.quote-expiry.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${amz.logistics.quote-expiry.interval-ms:21600000}")
    public void expireOutdatedQuotes() {
        if (!enabled) {
            return;
        }
        // 用幂等降级入口而非严格锁：本任务唯一的写是「把 ACTIVE 且已过期的置为 EXPIRED」，
        // 天然幂等——重复执行时第二次的 WHERE 条件不再命中，更新 0 行。
        // 因此 Redis 不可用时直接执行不会造成重复扣减/重复外部写，而严格锁会让
        // 报价在 Redis 故障期间一直不过期。与 SalesHistorySyncScheduler 同一判断。
        distributedJobLock.runIdempotentWithLock(LOCK_KEY, LOCK_LEASE_SECONDS, () -> {
            try {
                int updated = carrierQuoteMapper.expireOutdatedAcrossShops(LocalDate.now());
                if (updated > 0) {
                    log.info("过期报价跨店铺收口：{} 条置为 EXPIRED", updated);
                }
            } catch (Exception e) {
                // 显式捕获以留下业务上下文：调度线程抛出的异常只有堆栈，看不出是哪一轮哪件事。
                log.error("过期运价收口失败，下一轮重试", e);
            }
            return null;
        }, null);
    }
}
