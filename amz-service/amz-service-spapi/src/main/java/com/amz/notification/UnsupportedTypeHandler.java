package com.amz.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 兜底 Handler：捕获所有「还没写处理逻辑」的通知类型。
 * <p>
 * 为什么不让分发器直接抛异常了事：终态写 {@code UNSUPPORTED} 而不是 {@code DLQ}，
 * 是为了保留「这批事件没坏，只是我们当时没实现」这一信息。补上 Handler 后
 * 可以按类型批量重放；一旦混进 DLQ，事后没人分得清哪些死信其实只是未实现，
 * 只能整批重放或整批丢弃。
 * <p>
 * 顺序安全：实现 {@link #fallback()} 返回 true，分发器只在没有专属 Handler 时才用它，
 * 不依赖 Spring 注入 List 的先后顺序（{@link Order} 只是双保险）。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class UnsupportedTypeHandler implements NotificationEventHandler {

    @Override
    public boolean supports(String notificationType) {
        return true;
    }

    @Override
    public boolean fallback() {
        return true;
    }

    @Override
    public void handle(NotificationContext ctx) {
        log.info("[UnsupportedTypeHandler] 暂无 Handler 处理该类型，事件留证不重放："
                        + "notificationId={}, type={}, shopId={}",
                ctx.notificationId(), ctx.notificationType(), ctx.shopId());
        throw NotificationProcessingException.terminal(
                NotificationInboxStatus.UNSUPPORTED,
                NotificationEventDispatcher.ERR_UNSUPPORTED_TYPE,
                "没有 Handler 处理通知类型 " + ctx.notificationType());
    }
}
