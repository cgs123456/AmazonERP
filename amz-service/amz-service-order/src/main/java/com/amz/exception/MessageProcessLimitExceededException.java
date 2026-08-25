package com.amz.exception;

/**
 * 消息处理重试次数耗尽异常。
 * <p>
 * 由 {@code OrderServiceImpl.processOrderMessage} 在同一消息连续失败达到上限时抛出，
 * {@code OrderConsumer} 捕获后执行 basicNack(requeue=false)，经 DLX 路由到死信队列，
 * 避免毒消息无限重回队列头部阻塞消费（此前 requeue=true 会导致 DLX 永远不可达）。
 * 死信队列中的消息可人工排查后补发。
 */
public class MessageProcessLimitExceededException extends RuntimeException {

    public MessageProcessLimitExceededException(String message) {
        super(message);
    }

    public MessageProcessLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
