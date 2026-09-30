package com.amz.exception;

/**
 * 消息已被处理过（幂等跳过）——唯一的"可以直接 ack 丢弃"情形。
 * <p>
 * 存在动因：消费者此前用 {@code catch (IllegalStateException)} 作为"可以 ack"的判据，
 * 但 order 侧的 IllegalStateException 同时被用于<b>可重试</b>的故障
 * （product Feign 降级/超时 → "商品不存在或商品服务不可用"、Rabbit 发送失败 →
 * "凭证消息发送失败"、主键未回填 → "订单ID生成失败"）。
 * 于是这些消息被 ack 掉，永远不再投递 —— 与
 * {@code OrderServiceImpl.saveOrderInternal} 里"抛异常触发回滚，由 MQ 机制稍后重试"的注释意图正好相反。
 * 现在幂等跳过用本类型表达，其余异常一律走重投/死信路径。
 */
public class OrderMessageDuplicateException extends RuntimeException {

    public OrderMessageDuplicateException(String message) {
        super(message);
    }
}
