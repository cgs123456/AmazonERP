package com.amz.outbox;

import com.amz.client.SpApiGateway;
import com.amz.connector.ErrorSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox 重放执行器。
 * <p>
 * 自动调度只允许 GET/HEAD；POST/PUT/PATCH/DELETE 可能有远端副作用，必须由运维人员显式触发。
 * 无论自动还是人工，真正执行前都要通过数据库原子状态迁移领取记录，避免多实例重复调用。
 */
@Component
public class SpApiOutboxReplayExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpApiOutboxReplayExecutor.class);

    private final SpApiCallOutboxService outboxService;
    private final SpApiGateway gateway;

    public SpApiOutboxReplayExecutor(SpApiCallOutboxService outboxService, SpApiGateway gateway) {
        this.outboxService = outboxService;
        this.gateway = gateway;
    }

    /**
     * 重放一条记录。
     *
     * @param id        Outbox 主键
     * @param automatic true 为自动调度；false 为 OPERATOR/ADMIN 人工触发
     */
    public ReplayResult replay(Long id, boolean automatic) {
        if (id == null) {
            return ReplayResult.failure("INVALID_ID", null, "Outbox id is required");
        }

        SpApiCallOutboxService.ReplayCall call;
        try {
            call = outboxService.loadForReplay(id);
        } catch (RuntimeException e) {
            log.warn("Outbox replay load failed id={} automatic={} reason={}",
                    id, automatic, ErrorSummary.redact(e.getMessage()));
            return ReplayResult.failure("LOAD_FAILED", null, ErrorSummary.redact(e.getMessage()));
        }

        if (automatic && !isReadOnly(call.httpMethod())) {
            return ReplayResult.failure("SKIPPED_WRITE", call.originalStatus(),
                    "automatic replay only permits GET/HEAD");
        }
        if (automatic && SpApiCallOutboxService.DLQ.equals(call.originalStatus())) {
            return ReplayResult.failure("SKIPPED_DLQ", call.originalStatus(),
                    "DLQ records require explicit operator replay");
        }

        int claimed;
        try {
            claimed = outboxService.claimForReplay(id);
        } catch (RuntimeException e) {
            log.error("Outbox replay claim failed id={} automatic={}", id, automatic, e);
            return ReplayResult.failure("CLAIM_FAILED", call.originalStatus(), ErrorSummary.redact(e.getMessage()));
        }
        if (claimed != 1) {
            return ReplayResult.failure("SKIPPED_CLAIMED", call.originalStatus(),
                    "record is not claimable or another worker already claimed it");
        }

        try {
            gateway.replay(call);
            SpApiCallOutboxService.OutboxView view = safeView(id);
            String status = view == null ? SpApiCallOutboxService.REPLAYED : view.status();
            return ReplayResult.success(status, null);
        } catch (RuntimeException e) {
            // createReplay 成功时原记录已是 REPLAYED；此调用只对仍为 REPLAYING 的记录生效。
            releaseClaimQuietly(id);
            SpApiCallOutboxService.OutboxView view = safeView(id);
            String status = view == null ? call.originalStatus() : view.status();
            log.warn("Outbox replay execution failed id={} automatic={} status={} reason={}",
                    id, automatic, status, ErrorSummary.redact(e.getMessage()));
            return ReplayResult.failure("FAILED", status, ErrorSummary.redact(e.getMessage()));
        }
    }

    private SpApiCallOutboxService.OutboxView safeView(Long id) {
        try {
            return outboxService.view(id);
        } catch (RuntimeException e) {
            log.warn("Outbox replay view failed id={} reason={}", id, ErrorSummary.redact(e.getMessage()));
            return null;
        }
    }

    private void releaseClaimQuietly(Long id) {
        try {
            outboxService.releaseReplayClaim(id);
        } catch (RuntimeException releaseError) {
            log.error("Outbox replay claim release failed id={}", id, releaseError);
        }
    }

    private static boolean isReadOnly(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /** 单条重放的结构化结果，不包含请求体或密文。 */
    public record ReplayResult(boolean success, String outcome, String status, String message) {

        public static ReplayResult success(String status, String message) {
            return new ReplayResult(true, "SUCCEEDED", status, message);
        }

        public static ReplayResult failure(String outcome, String status, String message) {
            return new ReplayResult(false, outcome, status, message);
        }
    }
}
