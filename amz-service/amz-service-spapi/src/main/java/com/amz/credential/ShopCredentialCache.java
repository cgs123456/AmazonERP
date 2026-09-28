package com.amz.credential;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 店铺凭证的有界 TTL 缓存。
 * <p>
 * 只保存数据库中的密文凭证，不保存解密后的明文。缓存使用访问顺序
 * {@link LinkedHashMap}，超过上限时淘汰最久未访问的条目；TTL 到期后在下一次
 * 访问时惰性删除。所有读写都在同一个锁内完成，避免并发 put 突破容量上限。
 * <p>
 * 该缓存不负责数据库一致性：调用方必须先完成数据库写入或删除，再更新缓存。
 */
final class ShopCredentialCache {

    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final LongSupplier clock;
    private int maxEntries;
    private long ttlMillis;

    ShopCredentialCache() {
        this(1_000, 10 * 60 * 1000L, System::currentTimeMillis);
    }

    ShopCredentialCache(int maxEntries, long ttlMillis, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        configure(maxEntries, ttlMillis);
    }

    /**
     * 更新容量与 TTL。该方法只在启动初始化或测试装配时调用。
     */
    synchronized void configure(int maxEntries, long ttlMillis) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("credential cache maxEntries must be > 0");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("credential cache ttlMillis must be > 0");
        }
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
        evictExpired();
        evictOverflow();
    }

    synchronized ShopCredential get(Long shopId) {
        if (shopId == null) {
            return null;
        }
        Entry entry = entries.get(shopId);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(now())) {
            entries.remove(shopId);
            return null;
        }
        return entry.value();
    }

    synchronized void put(Long shopId, ShopCredential credential) {
        if (shopId == null || credential == null) {
            return;
        }
        entries.put(shopId, new Entry(credential, expiresAt(now())));
        evictExpired();
        evictOverflow();
    }

    synchronized void remove(Long shopId) {
        if (shopId != null) {
            entries.remove(shopId);
        }
    }

    synchronized Set<Long> keys() {
        evictExpired();
        return Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
    }

    synchronized int size() {
        evictExpired();
        return entries.size();
    }

    private void evictExpired() {
        long now = now();
        entries.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private void evictOverflow() {
        while (entries.size() > maxEntries) {
            Map.Entry<Long, Entry> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
        }
    }

    private long expiresAt(long now) {
        return now + ttlMillis;
    }

    private long now() {
        return clock.getAsLong();
    }

    private record Entry(ShopCredential value, long expiresAtMillis) {
        private boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }
}
