package com.amz.scheduler;

import com.amz.client.NotificationsClient;
import com.amz.mapper.NotificationSubscriptionMapper;
import com.amz.model.NotificationSubscriptionEntity;
import com.amz.notification.NotificationMetrics;
import com.amz.notification.NotificationProperties;
import com.amz.notification.NotificationSubscriptionDrift;
import com.amz.notification.NotificationSubscriptionRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 订阅对账调度：比对「本地登记的订阅映射」与「Amazon 侧真实订阅」。
 * <p>
 * 为什么需要它：订阅映射是通知链路的<b>唯一归属依据</b>，而它会被两侧独立修改——
 * 运维在 Seller Central 删了订阅、应用被重新授权、destination 被重建，
 * 本地表都不会自动知道。等发现问题时，表现是「某些店铺的通知一条都没有」，
 * 而此时 SQS 里的消息早已过期消失，无法补救。
 * <p>
 * 本调度<b>只做检测，不自动改状态</b>，这是刻意的选择：
 * <ul>
 *   <li>{@code MISSING_REMOTELY} 自动改成 DELETED 会让后续通知全部查不到映射，
 *       而真正的原因可能是「我们用错了 marketplaceId 查询」——自动修会把误判固化。</li>
 *   <li>{@code VERIFY_FAILED} 更不能当成「不存在」：无凭证 / 限流 / 网络抖动
 *       都会走到这里，把它判成已删除等于在故障期间批量删除映射。</li>
 * </ul>
 * 漂移结果写入日志、指标与 {@link #lastReport()}，由运维按报告补建或修正订阅。
 * <p>
 * 无真实凭证时的行为：每个订阅都会 {@code VERIFY_FAILED}，每小时告警一次。
 * 这是<b>期望行为</b>——它正是「还没接通」的证据，不要为了让告警安静而把它关掉或吞掉。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "spapi.notifications", name = "subscription-sync-enabled",
        havingValue = "true", matchIfMissing = true)
public class NotificationSubscriptionSyncScheduler {

    /** 单轮最多核对的订阅数，防止表被灌满时一次拉爆内存。 */
    public static final int MAX_SUBSCRIPTIONS_PER_RUN = 1000;

    private static final String REMOTE_FIELD_SUBSCRIPTION_ID = "subscriptionId";

    private final NotificationSubscriptionMapper subscriptionMapper;
    private final NotificationsClient notificationsClient;
    private final NotificationSubscriptionRegistry registry;
    private final NotificationMetrics metrics;
    private final NotificationProperties properties;
    private final AtomicReference<List<NotificationSubscriptionDrift>> lastReport =
            new AtomicReference<>(List.of());

    public NotificationSubscriptionSyncScheduler(NotificationSubscriptionMapper subscriptionMapper,
                                                 NotificationsClient notificationsClient,
                                                 NotificationSubscriptionRegistry registry,
                                                 NotificationMetrics metrics,
                                                 NotificationProperties properties) {
        this.subscriptionMapper = subscriptionMapper;
        this.notificationsClient = notificationsClient;
        this.registry = registry;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * 定时入口。吞掉异常是刻意的：对账属于旁路观测，
     * 一次数据库抖动不应该让调度线程把异常抛到 {@code TaskScheduler} 之外，
     * 更不应该影响同一进程内的其它调度任务。
     */
    @Scheduled(fixedDelayString = "${spapi.notifications.subscription-sync-interval-ms:3600000}",
            initialDelayString = "${spapi.notifications.subscription-sync-initial-delay-ms:60000}")
    public void run() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.error("[NotificationSubscriptionSync] 对账轮次异常结束：error={}，下一轮继续",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 执行一轮对账。
     *
     * @return 本轮发现的漂移；空列表表示一致或链路未启用
     */
    public List<NotificationSubscriptionDrift> sync() {
        if (!properties.isEnabled()) {
            log.debug("[NotificationSubscriptionSync] 通知链路未启用，跳过对账");
            return List.of();
        }
        List<NotificationSubscriptionEntity> locals = loadActiveSubscriptions();
        List<NotificationSubscriptionDrift> drift = new ArrayList<>();
        for (NotificationSubscriptionEntity row : locals) {
            NotificationSubscriptionDrift found = verify(row);
            if (found != null) {
                drift.add(found);
                metrics.subscriptionDrift(found.kind().name());
            }
        }
        List<NotificationSubscriptionDrift> report = List.copyOf(drift);
        lastReport.set(report);
        if (report.isEmpty()) {
            log.info("[NotificationSubscriptionSync] 订阅对账一致：本地有效订阅 {} 条", locals.size());
        } else {
            log.warn("[NotificationSubscriptionSync] 订阅对账发现 {} 处漂移（本地有效订阅 {} 条）："
                            + "这些订阅对应的通知可能无法归属店铺，请按 lastReport 补建或修正",
                    report.size(), locals.size());
        }
        return report;
    }

    /** 最近一轮的漂移报告；从未跑过时为空列表。 */
    public List<NotificationSubscriptionDrift> lastReport() {
        return lastReport.get();
    }

    private List<NotificationSubscriptionEntity> loadActiveSubscriptions() {
        List<NotificationSubscriptionEntity> rows = subscriptionMapper.selectList(
                new LambdaQueryWrapper<NotificationSubscriptionEntity>()
                        .eq(NotificationSubscriptionEntity::getStatus,
                                NotificationSubscriptionRegistry.STATUS_ACTIVE)
                        .last("LIMIT " + MAX_SUBSCRIPTIONS_PER_RUN));
        return rows == null ? List.of() : rows;
    }

    private NotificationSubscriptionDrift verify(NotificationSubscriptionEntity row) {
        String subscriptionId = row.getSubscriptionId();
        if (isBlank(subscriptionId) || row.getShopId() == null || isBlank(row.getNotificationType())) {
            return new NotificationSubscriptionDrift(subscriptionId, row.getShopId(),
                    row.getNotificationType(),
                    NotificationSubscriptionDrift.Kind.INCOMPLETE_LOCAL_RECORD,
                    "本地映射缺 subscriptionId / shopId / notificationType，无法发起校验");
        }
        // 合成演练数据绝不能拿去打真实 Amazon API：它会污染调用配额，
        // 而且返回的一定是「查不到」，制造出假漂移。
        if (row.getSynthetic() != null && row.getSynthetic() == 1) {
            return null;
        }
        try {
            JsonObject remote = notificationsClient.getSubscriptionById(
                    row.getShopId(), row.getMarketplaceId(), row.getNotificationType(), subscriptionId);
            if (remote != null && remote.has(REMOTE_FIELD_SUBSCRIPTION_ID)
                    && subscriptionId.equals(remote.get(REMOTE_FIELD_SUBSCRIPTION_ID).getAsString())) {
                return null;
            }
            registry.invalidate(subscriptionId);
            return new NotificationSubscriptionDrift(subscriptionId, row.getShopId(),
                    row.getNotificationType(),
                    NotificationSubscriptionDrift.Kind.MISSING_REMOTELY,
                    "Amazon 侧查不到该 subscriptionId，本地登记已失效");
        } catch (RuntimeException e) {
            // 校验失败不等于订阅不存在。绝不在这里改本地状态，也不清缓存。
            return new NotificationSubscriptionDrift(subscriptionId, row.getShopId(),
                    row.getNotificationType(),
                    NotificationSubscriptionDrift.Kind.VERIFY_FAILED,
                    "校验调用失败：" + e.getClass().getSimpleName());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}