package com.amz.notification;

import com.amz.client.FeedsClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FEED_PROCESSING_FINISHED 处理器：feed 处理完成后拉取处理结果并留证。
 * <p>
 * 只有 processingStatus=DONE 才去取结果：IN_PROGRESS / FATAL 的通知取结果必然报错，
 * 把这类通知判成可重试错误会让它在重试队列里空转到最后进 DLQ。
 * <p>
 * 注意这里的边界：本 Handler 只负责「取回并核对结果」，不做 Listing 修正动作——
 * 写操作涉及改价改库存，必须在有真实凭证与沙箱验证后才允许接，不属本任务范围。
 */
@Slf4j
@Component
public class FeedFinishedHandler implements NotificationEventHandler {

    /** 支持的通知类型。 */
    public static final String TYPE = "FEED_PROCESSING_FINISHED";

    /** Payload 内的业务节点名。 */
    public static final String PAYLOAD_NODE = "FeedProcessingFinishedNotification";

    /** 缺少业务节点。 */
    public static final String ERR_MISSING_NODE = "MISSING_FEED_NODE";
    /** 缺少 feedId。 */
    public static final String ERR_MISSING_FEED_ID = "MISSING_FEED_ID";
    /** 拉取结果失败（通常可重试）。 */
    public static final String ERR_FETCH_FAILED = "FEED_RESULT_FETCH_FAILED";

    private final FeedsClient feedsClient;

    public FeedFinishedHandler(FeedsClient feedsClient) {
        this.feedsClient = feedsClient;
    }

    @Override
    public boolean supports(String notificationType) {
        return TYPE.equalsIgnoreCase(notificationType);
    }

    @Override
    public void handle(NotificationContext ctx) {
        JsonNode node = ctx.payload() == null ? null : ctx.payload().get(PAYLOAD_NODE);
        if (node == null || !node.isObject()) {
            throw NotificationProcessingException.fatal(ERR_MISSING_NODE,
                    "FEED_PROCESSING_FINISHED 缺少 " + PAYLOAD_NODE + " 节点");
        }
        String feedId = text(node, "feedId");
        if (feedId == null) {
            throw NotificationProcessingException.fatal(ERR_MISSING_FEED_ID, "通知缺少 feedId");
        }
        String status = text(node, "processingStatus");
        if (!"DONE".equalsIgnoreCase(status)) {
            log.info("[FeedFinishedHandler] feed 未终态，跳过取结果：feedId={}, processingStatus={}",
                    feedId, status);
            return;
        }
        if (ctx.shopId() == null) {
            throw NotificationProcessingException.terminal(
                    NotificationInboxStatus.UNRESOLVED_SUBSCRIPTION, "UNRESOLVED_SHOP",
                    "feed 事件未归属店铺，补订阅映射后重放");
        }
        try {
            var result = feedsClient.fetchFeedResult(ctx.shopId(), feedId);
            log.info("[FeedFinishedHandler] feed 结果已取回：feedId={}, processed={}, accepted={}, invalid={}",
                    feedId, result == null ? null : result.getMessagesProcessed(),
                    result == null ? null : result.getMessagesAccepted(),
                    result == null ? null : result.getMessagesInvalid());
        } catch (NotificationProcessingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw NotificationProcessingException.retryable(ERR_FETCH_FAILED,
                    "拉取 feed 结果失败：" + e.getClass().getSimpleName());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }
}
