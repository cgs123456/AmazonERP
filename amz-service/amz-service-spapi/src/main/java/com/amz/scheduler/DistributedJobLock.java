package com.amz.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 定时任务分布式锁。
 * <p>
 * 多实例部署时，{@code @Scheduled} 任务会在每个实例各自触发一次：
 * 订单/库存同步与补货重算会双倍消耗 SP-API 配额并产生重复写。
 * 本组件基于 Redisson {@link RLock} 提供「抢不到即跳过」的互斥执行语义：
 * <ul>
 *   <li>tryLock(waitTime=0)：不排队等待，错峰触发直接让位；</li>
 *   <li>leaseTime 略小于调度周期：实例崩溃后锁自动过期，不阻塞下一轮；</li>
 *   <li>RedissonClient 缺失（单测 / 无 Redis 环境）时退化为直接执行。</li>
 * </ul>
 */
@Slf4j
@Component
public class DistributedJobLock {

    /** 锁默认租期：30 分钟。各任务按自身调度周期传入更精确的值。 */
    private static final long DEFAULT_LEASE_SECONDS = TimeUnit.MINUTES.toSeconds(30);

    @Autowired(required = false)
    private RedissonClient redissonClient;

    /**
     * 在分布式锁保护下执行任务。
     *
     * @param lockKey  锁名（建议 amz:sched:* 前缀）
     * @param leaseSeconds 锁租期（秒），应略小于调度周期
     * @param action   任务体
     * @param fallback 未获取到锁时的返回值（跳过语义）
     */
    public <T> T runWithLock(String lockKey, long leaseSeconds, Supplier<T> action, T fallback) {
        if (redissonClient == null) {
            // 单元测试 / 无 Redis 环境：退化为本地直执
            return action.get();
        }
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取调度锁被中断，跳过本轮：lock={}", lockKey);
            return fallback;
        }
        if (!acquired) {
            log.info("另一实例持有调度锁，本实例跳过本轮：lock={}", lockKey);
            return fallback;
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("释放调度锁失败（租期到自动过期）：lock={}", lockKey, e);
                }
            }
        }
    }

    /**
     * 使用默认 30 分钟租期的简化入口。
     */
    public <T> T runWithLock(String lockKey, Supplier<T> action, T fallback) {
        return runWithLock(lockKey, DEFAULT_LEASE_SECONDS, action, fallback);
    }

    /**
     * 无返回值任务的锁保护执行（内部以占位值复用 Supplier 逻辑）。
     */
    public void runWithLock(String lockKey, long leaseSeconds, Runnable action) {
        runWithLock(lockKey, leaseSeconds, () -> {
            action.run();
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }
}
