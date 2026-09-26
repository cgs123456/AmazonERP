package com.amz.notification;

import com.amz.mapper.NotificationInboxMapper;
import com.amz.model.NotificationInboxEntity;
import com.amz.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 入站通知 Worker：原子领取 -> 处理 -> 定终态 / 退避 / DLQ。
 * <p>
 * 领取必须是<b>条件 UPDATE</b>而不是「先查再改」：多实例部署下两个 Worker 同时看到
 * 同一条 RECEIVED 记录，先查再改会让两边都认为自己是领取者，业务副作用执行两次。
 * 条件 UPDATE 把判断与写入压进一条 SQL，由行锁保证只有一个实例拿到 1 行影响行数。
 * <p>
 * 租约（{@code lease_owner} / {@code lease_until}）解决的是另一类问题：
 * 进程被 kill、线程卡死时记录会永远停在 PROCESSING，没人再碰它。
 * 租约到期后由 {@link #recoverStaleLeases(LocalDateTime)} 回收重投。
 * <p>
 * 硬规则：日志只输出 notificationId / notificationType / shopId / 错误码，
 * 禁止输出通知正文——订单类通知含买家姓名与地址。
 */
@Slf4j
@Service
public class NotificationInboxWorker {

    /** 处理器未用 {@link NotificationProcessingException} 表达语义时的兜底错误码。 */
    public static final String ERR_UNEXPECTED = "UNEXPECTED";

    private final NotificationInboxMapper inboxMapper;
    private final CryptoUtil cryptoUtil;
    private final NotificationProperties properties;
    private final NotificationRetryPolicy retryPolicy;
    private final NotificationEventProcessor processor;
    private final String leaseOwner;

    public NotificationInboxWorker(NotificationInboxMapper inboxMapper,
                                   CryptoUtil cryptoUtil,
                                   NotificationProperties properties,
                                   NotificationEventProcessor processor) {
        this.inboxMapper = inboxMapper;
        this.cryptoUtil = cryptoUtil;
        this.properties = properties;
        this.retryPolicy = new NotificationRetryPolicy(properties.getBaseDelaySeconds());
        this.processor = processor;
        this.leaseOwner = defaultLeaseOwner();
    }

    /** 当前实例持有的租约标识，便于回收时定位是哪个实例卡住了。 */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /**
     * 领取并尝试处理一批到期事件。
     *
     * @param batchSize 本轮最多领取条数
     * @return 实际尝试处理（已成功领取）的条数
     */
    public int runBatch(int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        int handled = 0;
        for (Long id : findDueIds(batchSize, now)) {
            if (!claim(id, now)) {
                continue;
            }
            NotificationInboxEntity event = inboxMapper.selectById(id);
            if (event == null) {
                log.warn("[NotificationInboxWorker] 领取成功但回查为空，跳过：id={}", id);
                continue;
            }
            handled++;
            process(event);
        }
        return handled;
    }

    /** 查询到期可领取的事件 ID（只取 ID，正文留给领取后回查，避免在未持租约时搬运大字段）。 */
    public List<Long> findDueIds(int batchSize, LocalDateTime now) {
        List<NotificationInboxEntity> rows = inboxMapper.selectList(
                new LambdaQueryWrapper<NotificationInboxEntity>()
                        .eq(NotificationInboxEntity::getStatus, NotificationInboxStatus.RECEIVED)
                        .le(NotificationInboxEntity::getNextAttemptAt, now)
                        .orderByAsc(NotificationInboxEntity::getId)
                        .last("LIMIT " + Math.max(1, batchSize)));
        List<Long> ids = new ArrayList<>(rows.size());
        for (NotificationInboxEntity row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    /**
     * 原子领取单条事件。
     *
     * @return true 表示本实例取得处理权；false 表示已被别的实例抢走
     */
    public boolean claim(Long id, LocalDateTime now) {
        int rows = inboxMapper.update(null, new UpdateWrapper<NotificationInboxEntity>()
                .eq("id", id)
                .eq("status", NotificationInboxStatus.RECEIVED)
                .le("next_attempt_at", now)
                .set("status", NotificationInboxStatus.PROCESSING)
                .set("lease_owner", leaseOwner)
                .set("lease_until", now.plusSeconds(properties.getLeaseTimeoutSeconds()))
                .setSql("attempt_count = attempt_count + 1"));
        return rows == 1;
    }

    /**
     * 回收过期租约：进程被强杀 / 线程卡死后，记录会永远停在 PROCESSING。
     *
     * @return 被回收的条数
     */
    public int recoverStaleLeases(LocalDateTime now) {
        int rows = inboxMapper.update(null, new UpdateWrapper<NotificationInboxEntity>()
                .eq("status", NotificationInboxStatus.PROCESSING)
                .lt("lease_until", now)
                .set("status", NotificationInboxStatus.RECEIVED)
                .setSql("lease_owner = NULL, lease_until = NULL"));
        if (rows > 0) {
            log.warn("[NotificationInboxWorker] 回收过期租约 {} 条：这些事件曾由异常退出的实例持有，"
                    + "请核对实例是否存在 OOM / 强杀 / 长停顿。", rows);
        }
        return rows;
    }

    private void process(NotificationInboxEntity event) {
        try {
            processor.process(event, cryptoUtil.decrypt(event.getPayloadEncrypted()));
            markProcessed(event);
        } catch (NotificationProcessingException e) {
            finish(event, e.isRetryable(), e.getErrorCode(), e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            // 兜底按可重试处理：绝大多数运行时异常是下游暂时不可用。
            // 只记录类名不记录 message——异常消息可能拼接了通知正文，而正文含 PII。
            finish(event, true, ERR_UNEXPECTED, e.getClass().getSimpleName());
        }
    }

    private void markProcessed(NotificationInboxEntity event) {
        inboxMapper.update(null, new UpdateWrapper<NotificationInboxEntity>()
                .eq("id", event.getId())
                .set("status", NotificationInboxStatus.PROCESSED)
                .set("processed_at", LocalDateTime.now())
                .setSql("lease_owner = NULL, lease_until = NULL"));
        log.info("[NotificationInboxWorker] 处理完成：id={}, notificationId={}, type={}, shopId={}",
                event.getId(), event.getNotificationId(), event.getNotificationType(), event.getShopId());
    }

    private void finish(NotificationInboxEntity event, boolean retryable, String errorCode, String errorSummary) {
        int attempt = event.getAttemptCount() == null ? 1 : Math.max(1, event.getAttemptCount());
        int maxAttempts = event.getMaxAttempts() == null || event.getMaxAttempts() <= 0
                ? properties.getMaxAttempts()
                : event.getMaxAttempts();
        boolean deadLetter = !retryable || retryPolicy.shouldDeadLetter(attempt, maxAttempts);
        UpdateWrapper<NotificationInboxEntity> update = new UpdateWrapper<NotificationInboxEntity>()
                .eq("id", event.getId())
                .set("last_error_code", errorCode)
                .set("last_error_message", truncate(errorSummary, 200))
                .setSql("lease_owner = NULL, lease_until = NULL");
        if (deadLetter) {
            update.set("status", NotificationInboxStatus.DLQ);
            log.error("[NotificationInboxWorker] 事件进入 DLQ：id={}, notificationId={}, type={}, "
                            + "attempt={}, maxAttempts={}, retryable={}, errorCode={}",
                    event.getId(), event.getNotificationId(), event.getNotificationType(),
                    attempt, maxAttempts, retryable, errorCode);
        } else {
            long delay = retryPolicy.nextDelaySeconds(attempt);
            update.set("status", NotificationInboxStatus.RECEIVED);
            update.set("next_attempt_at", LocalDateTime.now().plusSeconds(delay));
            log.warn("[NotificationInboxWorker] 处理失败待重试：id={}, notificationId={}, attempt={}/{}, "
                            + "nextDelaySeconds={}, errorCode={}",
                    event.getId(), event.getNotificationId(), attempt, maxAttempts, delay, errorCode);
        }
        inboxMapper.update(null, update);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String defaultLeaseOwner() {
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            return runtime;
        }
        return host + ":" + runtime;
    }
}