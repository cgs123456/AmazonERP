package com.amz.constant;

/**
 * RabbitMQ 常量。
 * <p>
 * 已清理社交场景的 LIKE/COLLECTION/ATTENTION 队列，
 * 保留 ERP 业务所需的订单与库存消息队列。
 */
public class MqConstant {
    public final static String MESSAGE_NOTICE_EXCHANGE = "message.notice.exchange";
    public final static String LOGIN_NOTICE_QUEUE = "login.notice.queue";
    public final static String LOGIN_KEY = "login.key";

    public static final String SAVE_ORDER_EXCHANGE = "save.order.exchange";
    public static final String SAVE_ORDER_QUEUE = "save.order.queue";

    public static final String UPDATE_PRODUCT_STOCK_EXCHANGE = "update.product.stock.exchange";
    public static final String UPDATE_PRODUCT_STOCK_QUEUE = "update.product.stock.queue";
}
