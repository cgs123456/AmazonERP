package com.amz.lock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedJobLockTest {

    private static final String LOCK_KEY = "amz:sched:test";
    private static final long LEASE_SECONDS = 60L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SimpleMeterRegistry meterRegistry;
    private DistributedJobLock lock;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lock = new DistributedJobLock(redisTemplate, meterRegistry);
        calls = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("Redis 可用且抢锁成功时执行业务动作并释放自己的锁")
    void executesWhenLockAcquired() {
        stubAcquire(true);

        String result = lock.runWithLock(LOCK_KEY, LEASE_SECONDS, () -> {
            calls.incrementAndGet();
            return "ok";
        }, "fallback");

        assertEquals("ok", result);
        assertEquals(1, calls.get());
        verify(valueOperations).setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(LEASE_SECONDS)));
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("其他实例持锁时跳过本轮，不执行动作")
    void skipsWhenLockHeld() {
        stubAcquire(false);

        String result = lock.runWithLock(LOCK_KEY, LEASE_SECONDS, () -> {
            calls.incrementAndGet();
            return "should-not-run";
        }, "fallback");

        assertEquals("fallback", result);
        assertEquals(0, calls.get());
        assertEquals(1.0, counter("amz.scheduler.lock.skipped", "lock_held"));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("Redis 获取锁异常时默认 fail-closed，不执行业务动作")
    void redisErrorFailsClosedByDefault() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(LEASE_SECONDS))))
                .thenThrow(new RedisConnectionFailureException("down"));

        String result = lock.runWithLock(LOCK_KEY, LEASE_SECONDS, () -> {
            calls.incrementAndGet();
            return "should-not-run";
        }, "fallback");

        assertEquals("fallback", result);
        assertEquals(0, calls.get());
        assertEquals(1.0, counter("amz.scheduler.lock.acquire.failed", "redis_error"));
        assertEquals(0.0, counter("amz.scheduler.lock.degraded", "redis_error"));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("显式幂等任务在 Redis 异常时允许降级执行并记录指标")
    void idempotentTaskCanDegradeWhenRedisUnavailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(LEASE_SECONDS))))
                .thenThrow(new RedisConnectionFailureException("down"));

        String result = lock.runIdempotentWithLock(LOCK_KEY, LEASE_SECONDS, () -> {
            calls.incrementAndGet();
            return "ok";
        }, "fallback");

        assertEquals("ok", result);
        assertEquals(1, calls.get());
        assertEquals(1.0, counter("amz.scheduler.lock.acquire.failed", "redis_error"));
        assertEquals(1.0, counter("amz.scheduler.lock.degraded", "redis_error"));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("RedisTemplate 缺失时默认 fail-closed，不把无锁环境当单实例")
    void missingRedisTemplateFailsClosedByDefault() {
        DistributedJobLock noRedis = new DistributedJobLock(null, meterRegistry);

        String result = noRedis.runWithLock(LOCK_KEY, LEASE_SECONDS, () -> {
            calls.incrementAndGet();
            return "should-not-run";
        }, "fallback");

        assertEquals("fallback", result);
        assertEquals(0, calls.get());
        assertEquals(1.0, counter("amz.scheduler.lock.acquire.failed", "redis_template_missing"));
    }

    @Test
    @DisplayName("显式幂等任务在 RedisTemplate 缺失时可降级执行")
    void idempotentTaskCanDegradeWithoutRedisTemplate() {
        DistributedJobLock noRedis = new DistributedJobLock(null, meterRegistry);

        String result = noRedis.runIdempotentWithLock(LOCK_KEY, LEASE_SECONDS, () -> {
            calls.incrementAndGet();
            return "ok";
        }, "fallback");

        assertEquals("ok", result);
        assertEquals(1, calls.get());
        assertEquals(1.0, counter("amz.scheduler.lock.degraded", "redis_template_missing"));
    }

    @Test
    @DisplayName("释放锁异常不能覆盖已成功完成的业务结果")
    void releaseFailureDoesNotMaskActionResult() {
        stubAcquire(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("unlock down"));

        String result = lock.runWithLock(LOCK_KEY, LEASE_SECONDS, () -> "ok", "fallback");

        assertEquals("ok", result);
        assertEquals(1.0, counter("amz.scheduler.lock.release.failed", "redis_error"));
    }

    @Test
    @DisplayName("业务动作异常时仍尝试释放锁并保留原异常")
    void actionFailureStillReleasesLock() {
        stubAcquire(true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> lock.runWithLock(LOCK_KEY, LEASE_SECONDS, () -> {
                    throw new IllegalStateException("action failed");
                }, "fallback"));

        assertEquals("action failed", failure.getMessage());
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("非法租期直接拒绝，避免创建永不过期或立刻过期的锁")
    void invalidLeaseIsRejectedBeforeCallingRedis() {
        assertThrows(IllegalArgumentException.class,
                () -> lock.runWithLock(LOCK_KEY, 0L, () -> "should-not-run", "fallback"));

        assertEquals(0, calls.get());
        verify(redisTemplate, never()).opsForValue();
    }

    private void stubAcquire(boolean acquired) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(LEASE_SECONDS))))
                .thenReturn(acquired);
    }

    private double counter(String name, String reason) {
        var counter = meterRegistry.find(name).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
