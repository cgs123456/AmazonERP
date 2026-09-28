package com.amz.scheduler;

import com.amz.outbox.SpApiCallOutboxService;
import com.amz.outbox.SpApiOutboxReplayExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SP-API Outbox 自动重放调度器。
 * <p>
 * 只处理到期的 FAILED 记录，且执行器会再次拒绝非 GET/HEAD；DLQ 必须人工重放。
 * 多实例竞争由数据库原子领取保证，不依赖本进程锁。
 */
@Component
@Profile("!bootstrap")

@ConditionalOnProperty(prefix = "spapi.outbox", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class SpApiOutboxReplayScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpApiOutboxReplayScheduler.class);

    private final SpApiCallOutboxService outboxService;
    private final SpApiOutboxReplayExecutor executor;
    private final int batchSize;
    private final long staleClaimSeconds;

    public SpApiOutboxReplayScheduler(SpApiCallOutboxService outboxService,
                                      SpApiOutboxReplayExecutor executor,
                                      @Value("${spapi.outbox.batch-size:50}") int batchSize,
                                      @Value("${spapi.outbox.replay-claim-timeout-seconds:900}")
                                      long staleClaimSeconds) {
        this.outboxService = outboxService;
        this.executor = executor;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.staleClaimSeconds = Math.max(60L, staleClaimSeconds);
    }

    @Scheduled(fixedDelayString = "${spapi.outbox.scheduler-delay-ms:60000}")
    public void replayDue() {
        try {
            int recovered = outboxService.recoverStaleReplayClaims(
                    LocalDateTime.now().minusSeconds(staleClaimSeconds));
            if (recovered > 0) {
                log.warn("Recovered {} stale SP-API Outbox replay claim(s) into DLQ", recovered);
            }
        } catch (RuntimeException e) {
            log.error("SP-API Outbox stale replay recovery failed", e);
        }

        List<Long> ids;
        try {
            ids = outboxService.dueIds(batchSize);
        } catch (RuntimeException e) {
            log.error("SP-API Outbox due query failed", e);
            return;
        }
        for (Long id : ids) {
            SpApiOutboxReplayExecutor.ReplayResult result = executor.replay(id, true);
            if (result.success()) {
                log.info("SP-API Outbox replay succeeded id={} status={}", id, result.status());
            } else if (!"SKIPPED_CLAIMED".equals(result.outcome())) {
                log.warn("SP-API Outbox replay skipped/failed id={} outcome={} status={} message={}",
                        id, result.outcome(), result.status(), result.message());
            }
        }
    }
}
