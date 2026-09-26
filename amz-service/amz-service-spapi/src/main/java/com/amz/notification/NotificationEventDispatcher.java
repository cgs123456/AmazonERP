package com.amz.notification;

import com.amz.model.NotificationInboxEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知分发器：Worker 领到事件后，按类型路由到对应 Handler。
 * <p>
 * 两类「不处理」必须区分清楚，混在一起会让后续无法补救：
 * <ul>
 *   <li>类型未实现 -> {@code UNSUPPORTED}：事件没坏，是我们还没写处理逻辑，补上后可批量重放。</li>
 *   <li>事件本身坏了 -> {@code DLQ}：正文缺关键字段，重放一万次也一样。</li>
 * </ul>
 * <p>
 * 选择 Handler 时先找专属实现、再回退到 {@link #fallback()} 的兜底实现，
 * 因此不依赖 Spring 注入 List 的先后顺序（顺序错了会把全部事件判成 UNSUPPORTED）。
 */
@Slf4j
@Service
public class NotificationEventDispatcher implements NotificationEventProcessor {

    /** 正文缺失或不是合法 JSON（超限事件未存正文，不应被领取；这里是兜底）。 */
    public static final String ERR_EMPTY_PAYLOAD = "EMPTY_PAYLOAD";
    /** 没有任何 Handler 认领该类型。 */
    public static final String ERR_UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE";

    private final ObjectMapper objectMapper;
    private final List<NotificationEventHandler> handlers;

    public NotificationEventDispatcher(ObjectMapper objectMapper,
                                       List<NotificationEventHandler> handlers) {
        this.objectMapper = objectMapper;
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    @Override
    public void process(NotificationInboxEntity event, String rawPayloadJson) {
        JsonNode payload = parsePayload(rawPayloadJson);
        NotificationContext ctx = new NotificationContext(
                event.getId(),
                event.getNotificationId(),
                event.getNotificationType(),
                event.getEventTime(),
                event.getPublishTime(),
                event.getShopId(),
                event.getMarketplaceId(),
                event.getSubscriptionId(),
                event.getPayloadVersion(),
                event.getSynthetic() != null && event.getSynthetic() == 1,
                payload,
                rawPayloadJson);

        NotificationEventHandler handler = selectHandler(ctx.notificationType());
        if (handler == null) {
            throw NotificationProcessingException.terminal(
                    NotificationInboxStatus.UNSUPPORTED, ERR_UNSUPPORTED_TYPE,
                    "没有 Handler 处理通知类型 " + ctx.notificationType());
        }
        handler.handle(ctx);
    }

    /** 专属 Handler 优先；只有没有专属实现时才启用兜底 Handler。 */
    private NotificationEventHandler selectHandler(String notificationType) {
        for (NotificationEventHandler candidate : handlers) {
            if (!candidate.fallback() && candidate.supports(notificationType)) {
                return candidate;
            }
        }
        for (NotificationEventHandler candidate : handlers) {
            if (candidate.fallback()) {
                return candidate;
            }
        }
        return null;
    }

    private JsonNode parsePayload(String rawPayloadJson) {
        if (rawPayloadJson == null || rawPayloadJson.isBlank()) {
            throw NotificationProcessingException.fatal(ERR_EMPTY_PAYLOAD, "通知正文为空，无法处理");
        }
        try {
            JsonNode root = objectMapper.readTree(rawPayloadJson);
            JsonNode payload = root == null ? null : root.get("Payload");
            if (payload == null) {
                throw NotificationProcessingException.fatal(ERR_EMPTY_PAYLOAD,
                        "通知缺少 Payload 节点");
            }
            return payload;
        } catch (NotificationProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw NotificationProcessingException.fatal(ERR_EMPTY_PAYLOAD,
                    "通知正文不是合法 JSON：" + e.getClass().getSimpleName());
        }
    }
}