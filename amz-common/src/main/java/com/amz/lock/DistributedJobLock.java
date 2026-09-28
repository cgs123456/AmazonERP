package com.amz.lock;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
 *   <li><b>默认 fail-closed</b>：Redis 缺失、不可达或获取结果不确定时跳过本轮并返回 fallback，
 *       不允许写任务在无法确认全局互斥的情况下继续执行；</li>
 *   <li>仅明确声明为幂等/只读的任务，才可通过 {@link #runIdempotentWithLock(String, long, Supplier, Object)}
 *       显式选择降级执行；不得使用全局开关一次性放行所有任务。</li>
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

    private static final String METRIC_ACQUIRE_FAILED = "amz.scheduler.lock.acquire.failed";
    private static final String METRIC_DEGRADED = "amz.scheduler.lock.degraded";
    private static final String METRIC_SKIPPED = "amz.scheduler.lock.skipped";
    private static final String METRIC_RELEASE_FAILED = "amz.scheduler.lock.release.failed";

    private static final String REASON_REDIS_TEMPLATE_MISSING = "redis_template_missing";
    private static final String REASON_REDIS_ERROR = "redis_error";
    private static final String REASON_LOCK_HELD = "lock_held";

    /** 值匹配才删除：只释放自己持有的锁，不误删租期过期后被其他实例重新抢占的锁。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 软依赖：未引入 Micrometer 时退化为无指标模式，不影响锁语义。 */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * Spring/既有测试使用的无参构造。生产运行时由字段注入 Redis 与指标。
     */
    public DistributedJobLock() {
    }

    /**
     * 显式构造入口，便于单元测试和需要手工装配的场景。
     */
    public DistributedJobLock(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 在分布式锁保护下执行任务。Redis 不可用时默认 fail-closed。
     *
     * @param lockKey      锁名（建议 amz:sched:* 前缀）
     * @param leaseSeconds 锁租期（秒），应略小于调度周期
     * @param action       任务体
     * @param fallback     未获取到锁或锁基础设施不可用时的返回值（跳过语义）
     */
    public <T> T runWithLock(String lockKey, long leaseSeconds, Supplier<T> action, T fallback) {
        return runWithLockInternal(lockKey, leaseSeconds, action, fallback, false);
    }

    /**
     * 仅供明确声明为幂等或只读的任务使用：Redis 不可用时允许降级为直接执行。
     * <p>
     * 调用方必须证明 action 重复执行不会造成重复扣减、重复告警、重复外部写或不可接受的配额消耗。
     * 对存在外部写、MQ 发布、库存/资金变更或告警落库的任务，禁止使用本入口。
     */
    public <T> T runIdempotentWithLock(String lockKey, long leaseSeconds,
                                       Supplier<T> action, T fallback) {
        return runWithLockInternal(lockKey, leaseSeconds, action, fallback, true);
    }

    /**
     * 使用默认 30 分钟租期的简化入口。
     */
    public <T> T runWithLock(String lockKey, Supplier<T> action, T fallback) {
        return runWithLock(lockKey, DEFAULT_LEASE_SECONDS, action, fallback);
    }

    /**
     * 使用默认 30 分钟租期的显式幂等降级入口。
     */
    public <T> T runIdempotentWithLock(String lockKey, Supplier<T> action, T fallback) {
        return runIdempotentWithLock(lockKey, DEFAULT_LEASE_SECONDS, action, fallback);
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

    /**
     * 无返回值、明确幂等任务的显式降级执行入口。
     */
    public void runIdempotentWithLock(String lockKey, long leaseSeconds, Runnable action) {
        runIdempotentWithLock(lockKey, leaseSeconds, () -> {
            action.run();
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    private <T> T runWithLockInternal(String lockKey, long leaseSeconds,
                                      Supplier<T> action, T fallback,
                                      boolean allowIdempotentDegradation) {
        Objects.requireNonNull(lockKey, "lockKey");
        Objects.requireNonNull(action, "action");
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be > 0");
        }

        if (redisTemplate == null) {
            return handleLockUnavailable(lockKey, action, fallback,
                    allowIdempotentDegradation, REASON_REDIS_TEMPLATE_MISSING, null);
        }

        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, Duration.ofSeconds(leaseSeconds)));
        } catch (Exception e) {
            return handleLockUnavailable(lockKey, action, fallback,
                    allowIdempotentDegradation, REASON_REDIS_ERROR, e);
        }

        if (!acquired) {
            increment(METRIC_SKIPPED, REASON_LOCK_HELD);
            log.info("另一实例持有调度锁，本实例跳过本轮：lock={}", lockKey);
            return fallback;
        }

        try {
            return action.get();
        } finally {
            try {
                redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
            } catch (Exception e) {
                increment(METRIC_RELEASE_FAILED, REASON_REDIS_ERROR);
                log.warn("释放调度锁失败（租期到自动过期）：lock={}, err={}", lockKey, e.getMessage());
            }
        }
    }

    private <T> T handleLockUnavailable(String lockKey, Supplier<T> action, T fallback,
                                        boolean allowIdempotentDegradation,
                                        String reason, Exception cause) {
        increment(METRIC_ACQUIRE_FAILED, reason);
        if (!allowIdempotentDegradation) {
            if (cause == null) {
                log.error("调度锁不可用，按 fail-closed 跳过本轮：lock={}, reason={}", lockKey, reason);
            } else {
                log.error("调度锁获取失败，按 fail-closed 跳过本轮：lock={}, reason={}",
                        lockKey, reason, cause);
            }
            return fallback;
        }

        increment(METRIC_DEGRADED, reason);
        if (cause == null) {
            log.warn("调度锁不可用，按显式幂等任务策略降级执行：lock={}, reason={}", lockKey, reason);
        } else {
            log.warn("调度锁获取失败，按显式幂等任务策略降级执行：lock={}, reason={}",
                    lockKey, reason, cause);
        }
        return action.get();
    }

    private void increment(String metric, String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter(metric, "reason", reason).increment();
        }
    }
}
