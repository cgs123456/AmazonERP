package com.amz.constant;

/**
 * RabbitMQ 常量。
 * <p>
 * 已清理社交场景的 LIKE/COLLECTION/ATTENTION 队列及 B2C 商品库存同步队列，
 * 保留 ERP 业务所需的消息队列。
 */
public class MqConstant {
    public final static String MESSAGE_NOTICE_EXCHANGE = "message.notice.exchange";
    public final static String LOGIN_NOTICE_QUEUE = "login.notice.queue";
    public final static String LOGIN_KEY = "login.key";

    public static final String SAVE_ORDER_EXCHANGE = "save.order.exchange";
    public static final String SAVE_ORDER_QUEUE = "save.order.queue";
    /** 订单保存死信交换机/队列：消费失败（NACK requeue=false）的消息转入 DLQ */
    public static final String SAVE_ORDER_DLX_EXCHANGE = "save.order.dlx.exchange";
    public static final String SAVE_ORDER_DLQ_QUEUE = "save.order.dlq.queue";
    public static final String SAVE_ORDER_DLQ_ROUTING_KEY = "save.order.dlq";

    /** 登录通知死信交换机/队列 */
    public static final String LOGIN_DLX_EXCHANGE = "login.notice.dlx.exchange";
    public static final String LOGIN_DLQ_QUEUE = "login.notice.dlq.queue";
    public static final String LOGIN_DLQ_ROUTING_KEY = "login.notice.dlq";

    // ===== 利润核算链路 =====
    /** 利润核算交换机（spapi 生产 -> ProfitMQConsumer 消费） */
    public static final String PROFIT_EXCHANGE = "amz.order.profit.exchange";
    public static final String PROFIT_QUEUE = "amz.order.profit.queue";
    public static final String PROFIT_ROUTING_KEY = "amz.order.profit";
    /** 利润死信交换机/队列：消费失败（auto nack, 不 requeue）的消息转入 DLQ */
    public static final String PROFIT_DLX_EXCHANGE = "amz.order.profit.dlx.exchange";
    public static final String PROFIT_DLQ_QUEUE = "amz.order.profit.dlq.queue";
    public static final String PROFIT_DLQ_ROUTING_KEY = "amz.order.profit.dlq";
}