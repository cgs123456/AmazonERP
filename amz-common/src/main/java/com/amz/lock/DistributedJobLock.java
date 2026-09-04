package com.amz.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 定时任务分布式锁。
 * <p>
 * 多实例部署时，{@code @Scheduled} 任务会在每个实例各自触发一次：
 * 同步/重算类任务会双倍消耗外部配额并产生重复写（如分时调价双跑、告警重复落库）。
 * 本组件基于 Redis {@code SET NX EX} 提供「抢不到即跳过」的互斥执行语义：
 * <ul>
 *   <li>tryLock(waitTime=0) 等价的 setIfAbsent：不排队等待，错峰触发直接让位；</li>
 *   <li>leaseTime 略小于调度周期：实例崩溃后锁自动过期，不阻塞下一轮；</li>
 *   <li>释放走「值匹配才删」的 Lua 脚本，避免误删租期已过期、被其他实例持有的锁；</li>
 *   <li>StringRedisTemplate 缺失（单测 / 无 Redis 环境）或 Redis 不可达时退化为直接执行（fail-open）。</li>
 * </ul>
 * <p>
 * 依赖说明：amz-common 中 spring-data-redis 为 optional，运行时由各服务自身的
 * spring-boot-starter-data-redis 提供（当前全部业务服务均已声明）。
 */
@Slf4j
@Component
public class DistributedJobLock {

    /** 锁默认租期：30 分钟。各任务按自身调度周期传入更精确的值。 */
    private static final long DEFAULT_LEASE_SECONDS = TimeUnit.MINUTES.toSeconds(30);

    /** 值匹配才删除：只释放自己持有的锁，不误删租期过期后被其他实例重新抢占的锁。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * 在分布式锁保护下执行任务。
     *
     * @param lockKey  锁名（建议 amz:sched:* 前缀）
     * @param leaseSeconds 锁租期（秒），应略小于调度周期
     * @param action   任务体
     * @param fallback 未获取到锁时的返回值（跳过语义）
     */
    public <T> T runWithLock(String lockKey, long leaseSeconds, Supplier<T> action, T fallback) {
        if (redisTemplate == null) {
            // 单元测试 / 无 Redis 环境：退化为本地直执
            return action.get();
        }
        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, Duration.ofSeconds(leaseSeconds)));
        } catch (Exception e) {
            // Redis 不可达：fail-open 直接执行，避免基础设施故障阻断业务任务
            log.warn("调度锁获取失败（Redis 不可达），退化为直接执行：lock={}, err={}", lockKey, e.getMessage());
            acquired = true;
        }
        if (!acquired) {
            log.info("另一实例持有调度锁，本实例跳过本轮：lock={}", lockKey);
            return fallback;
        }
        try {
            return action.get();
        } finally {
            try {
                redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
            } catch (Exception e) {
                log.warn("释放调度锁失败（租期到自动过期）：lock={}, err={}", lockKey, e.getMessage());
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
