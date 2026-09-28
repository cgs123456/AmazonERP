package com.amz.order;

/** 订单 upsert 消息发布失败。发送失败必须让调用方感知，不能吞掉后假装同步成功。 */
public class OrderUpsertPublishException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrderUpsertPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
