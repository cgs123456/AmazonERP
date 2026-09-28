package com.amz.notification;

/**
 * 按通知类型分发的处理器。
 * <p>
 * 每个实现只认一种通知类型。新增类型 = 新增一个实现类，
 * 不需要改分发器——否则每接一种通知都要动一次中央 switch，迟早漏改。
 */
public interface NotificationEventHandler {

    /** 是否处理该通知类型。 */
    boolean supports(String notificationType);

    /**
     * 是否为兜底处理器（无专属 Handler 时才启用）。
     * <p>
     * 兜底处理器对所有类型都返回 true，若让它在 List 注入顺序里排在前面，
     * 会把所有事件都判成 UNSUPPORTED。显式声明兜底身份后，分发器先找专属
     * Handler、找不到才用兜底，不再依赖 Spring 的注入顺序。
     */
    default boolean fallback() {
        return false;
    }

    /**
     * 处理事件。
     *
     * @param ctx 事件上下文
     * @throws NotificationProcessingException 处理失败；可重试语义由异常表达
     */
    void handle(NotificationContext ctx);
}
