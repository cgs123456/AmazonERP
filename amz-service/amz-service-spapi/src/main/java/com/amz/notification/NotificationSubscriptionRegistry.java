package com.amz.notification;

import com.amz.mapper.NotificationSubscriptionMapper;
import com.amz.model.NotificationSubscriptionEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * subscriptionId -> 店铺绑定的反查注册表。
 * <p>
 * SP-API 的通知报文里只有 {@code NotificationMetadata.SubscriptionId}，
 * 没有 shopId、没有 marketplaceId。没有这张反查表，多店铺场景下唯一的结果是串店：
 * A 店的订单写进 B 店。所以「查不到」必须是一个<b>一等状态</b>，而不是当作异常忽略。
 * <p>
 * 查不到时返回 {@code Optional.empty()}，调用方把事件置为
 * {@link NotificationInboxStatus#UNRESOLVED_SUBSCRIPTION}：
 * 不删 SQS 消息（删了就永远丢了）、不进 DLQ（不是事件坏了，是我们的映射缺了）、
 * 补上映射后可以整批重放。
 * <p>
 * 缓存的两个刻意设计：
 * <ul>
 *   <li><b>负缓存</b>：查不到的 subscriptionId 一样进缓存。通知是突发流量，
 *       不缓存的话同一批未映射订阅会把数据库打穿，而结果永远是「查不到」。</li>
 *   <li><b>短 TTL</b>：默认 30 秒。映射补上之后最迟 30 秒生效，
 *       不需要重启实例，也不需要等长 TTL 过期。</li>
 * </ul>
 * 对账调度发现漂移后调用 {@link #invalidate(String)} 主动失效，不等 TTL。
 */
@Slf4j
@Component
public class NotificationSubscriptionRegistry {

    /** 本地登记表中代表有效订阅的状态值。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 默认缓存有效期：映射补上后最迟 30 秒生效。 */
    public static final long DEFAULT_CACHE_TTL_MILLIS = 30_000L;

    /** 反查最多取 2 行：多于 1 行说明唯一键被破坏，此时拒绝猜测归属。 */
    private static final int LOOKUP_LIMIT = 2;

    private final NotificationSubscriptionMapper subscriptionMapper;
    private final NotificationMetrics metrics;
    private final long cacheTtlMillis;
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public NotificationSubscriptionRegistry(NotificationSubscriptionMapper subscriptionMapper,
                                            NotificationMetrics metrics) {
        this(subscriptionMapper, metrics, DEFAULT_CACHE_TTL_MILLIS, System::currentTimeMillis);
    }

    NotificationSubscriptionRegistry(NotificationSubscriptionMapper subscriptionMapper,
                                     NotificationMetrics metrics,
                                     long cacheTtlMillis,
                                     LongSupplier clockMillis) {
        this.subscriptionMapper = Objects.requireNonNull(subscriptionMapper, "NotificationSubscriptionMapper");
        this.metrics = Objects.requireNonNull(metrics, "NotificationMetrics");
        this.cacheTtlMillis = cacheTtlMillis > 0 ? cacheTtlMillis : DEFAULT_CACHE_TTL_MILLIS;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /**
     * 反查 subscriptionId 归属的店铺。
     *
     * @param subscriptionId 报文中的 {@code NotificationMetadata.SubscriptionId}；null / 空白视为未映射
     * @return 店铺绑定；empty 表示未映射，调用方必须走 UNRESOLVED_SUBSCRIPTION 隔离
     */
    public Optional<NotificationShopBinding> resolve(String subscriptionId) {
        String key = subscriptionId == null ? "" : subscriptionId.trim();
        if (key.isEmpty()) {
            log.warn("[NotificationSubscriptionRegistry] 通知缺少 subscriptionId，无法归属店铺，转入 UNRESOLVED_SUBSCRIPTION");
            metrics.unresolvedSubscription();
            return Optional.empty();
        }
        long now = clockMillis.getAsLong();
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.expired(now)) {
            return cached.binding();
        }
        Optional<NotificationShopBinding> binding;
        try {
            binding = load(key);
        } catch (RuntimeException e) {
            // 数据库不可用时按「未映射」处理，但【不写缓存】：
            // 负缓存的语义是「确认没有映射」，而不是「这次没查到」。
            // 把一次抖动缓存成 30 秒未映射，等于在故障期批量把事件判成无法归属。
            log.error("[NotificationSubscriptionRegistry] 订阅映射查询失败，按未映射处理且不缓存：error={}",
                    e.getClass().getSimpleName());
            metrics.unresolvedSubscription();
            return Optional.empty();
        }
        cache.put(key, new CacheEntry(binding, now + cacheTtlMillis));
        if (binding.isEmpty()) {
            log.warn("[NotificationSubscriptionRegistry] subscriptionId 未映射到店铺：subscriptionId={}，"
                            + "事件将转入 UNRESOLVED_SUBSCRIPTION（不删 SQS 消息、不进 DLQ），"
                            + "补订阅映射后可重放", key);
            metrics.unresolvedSubscription();
        }
        return binding;
    }

    /** 主动失效单个订阅的缓存：对账发现漂移或运维补映射后调用。 */
    public void invalidate(String subscriptionId) {
        if (subscriptionId == null) {
            return;
        }
        cache.remove(subscriptionId.trim());
    }

    /** 全量失效缓存：批量补映射 / 重新导入订阅后调用。 */
    public void invalidateAll() {
        cache.clear();
    }

    /** 当前缓存条目数（含负缓存），仅用于观测与测试。 */
    public int cachedEntries() {
        return cache.size();
    }

    private Optional<NotificationShopBinding> load(String subscriptionId) {
        // 只查 ACTIVE：已删除 / 待下线的订阅不能继续给新事件归属。
        // 查询异常直接抛出，由调用方决定是否缓存——故障期不能缓存出「确认未映射」。
        List<NotificationSubscriptionEntity> rows =
                subscriptionMapper.selectList(new LambdaQueryWrapper<NotificationSubscriptionEntity>()
                        .eq(NotificationSubscriptionEntity::getSubscriptionId, subscriptionId)
                        .eq(NotificationSubscriptionEntity::getStatus, STATUS_ACTIVE)
                        .last("LIMIT " + LOOKUP_LIMIT));
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            log.error("[NotificationSubscriptionRegistry] subscriptionId 命中多条有效映射，拒绝猜测归属："
                            + "subscriptionId={}, 命中={}。请检查 uk_subscription_id 是否被破坏",
                    subscriptionId, rows.size());
            return Optional.empty();
        }
        NotificationSubscriptionEntity row = rows.get(0);
        // SQL 里已经过滤了 ACTIVE，这里再拦一次是刻意的双保险：
        // 将来若换成自定义 XML mapper 而漏掉 status 谓词，DELETED 订阅就会继续给事件归属，
        // 表现为「已下线的店铺还在收数据」——这类串店事故排查成本极高。
        if (!STATUS_ACTIVE.equalsIgnoreCase(row.getStatus())) {
            log.warn("[NotificationSubscriptionRegistry] 订阅映射非 ACTIVE，拒绝归属：subscriptionId={}, status={}",
                    subscriptionId, row.getStatus());
            return Optional.empty();
        }
        if (row.getShopId() == null) {
            log.error("[NotificationSubscriptionRegistry] 订阅映射缺少 shopId，无法归属：subscriptionId={}",
                    subscriptionId);
            return Optional.empty();
        }
        return Optional.of(new NotificationShopBinding(
                row.getShopId(), row.getMarketplaceId(), row.getDestinationId()));
    }

    /** 缓存条目；{@code binding} 为 empty 即负缓存。 */
    private record CacheEntry(Optional<NotificationShopBinding> binding, long expiresAtMillis) {

        private boolean expired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }
}