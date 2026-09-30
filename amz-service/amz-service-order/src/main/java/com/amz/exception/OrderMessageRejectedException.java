package com.amz.exception;

/**
 * 消息本身不合法，重投也不可能成功 —— 由 Consumer 直接转死信队列（nack requeue=false）。
 * <p>
 * 存在动因：这类"永久拒绝"以前用 {@code IllegalStateException} 表达，而 Consumer 把所有
 * {@code IllegalStateException} 当作"幂等跳过"直接 ack，于是坏消息被静默丢弃、无痕可查。
 * 现在把三类语义分开：幂等重复（ack）、永久不合法（DLQ）、可重试故障（requeue 计数后 DLQ）。
 */
public class OrderMessageRejectedException extends RuntimeException {

    public OrderMessageRejectedException(String message) {
        super(message);
    }

    public OrderMessageRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
