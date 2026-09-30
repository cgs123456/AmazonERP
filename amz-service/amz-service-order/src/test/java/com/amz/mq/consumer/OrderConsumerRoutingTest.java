package com.amz.mq.consumer;

import com.amz.exception.MessageProcessLimitExceededException;
import com.amz.exception.OrderMessageDuplicateException;
import com.amz.exception.OrderMessageRejectedException;
import com.amz.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 消费端 ack / 重投 / 死信的路由契约。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查）</b>：Consumer 用
 * {@code catch (IllegalStateException) -> ack} 表达"幂等跳过"，但 order 侧同一个异常类型
 * 还用于<b>可重试</b>故障 —— {@code OrderServiceImpl.saveOrderInternal} 的
 * "商品不存在或商品服务不可用"正是 product Feign 降级/超时的快速失败，
 * 同一段代码上方的注释写着"抛异常触发回滚，由 MQ 机制稍后重试"。
 * 两者相遇的结果是：这些订单消息被 ack 后<b>永久消失</b>，与代码意图完全相反。
 * <p>
 * 现在三类语义各有出口：幂等重复 → ack；消息不合法 → 死信；可重试故障 → requeue（计数达上限后死信）。
 * 本测试同时防止矫枉过正：不合法消息不能变成无限重投。
 */
@DisplayName("订单消息消费路由：ack、重投与死信必须按语义分开")
@ExtendWith(MockitoExtension.class)
class OrderConsumerRoutingTest {

    @Mock
    private OrderService orderService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderConsumer consumer;

    @Mock
    private Channel channel;

    private static final long TAG = 5L;

    @Test
    @DisplayName("幂等重复：ack 且不重投")
    void duplicateMessageIsAcked() throws Exception {
        doThrow(new OrderMessageDuplicateException("消息已处理过"))
                .when(orderService).processOrderMessage(any());

        consume();

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("消息不合法：转死信（nack requeue=false），不 ack 丢弃也不无限重投")
    void permanentlyInvalidMessageGoesToDlq() throws Exception {
        doThrow(new OrderMessageRejectedException("用户ID不能为空"))
                .when(orderService).processOrderMessage(any());

        consume();

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("可重试故障（含商品服务不可用的 IllegalStateException）：必须 requeue，不许 ack")
    void transientFailureIsRequeuedNotDropped() throws Exception {
        // 这条就是修复前丢单的形状：旧实现把它当"参数校验"直接 ack
        doThrow(new IllegalStateException("商品不存在或商品服务不可用"))
                .when(orderService).processOrderMessage(any());

        consume();

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("重试次数达上限：转死信，避免毒消息阻塞队列")
    void exhaustedRetriesGoToDlq() throws Exception {
        doThrow(new MessageProcessLimitExceededException("连续失败 3 次"))
                .when(orderService).processOrderMessage(any());

        consume();

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("处理成功：ack 且不 nack")
    void successfulProcessingAcks() throws Exception {
        consume();

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("消息体不是合法 JSON：转死信，不能进通用 requeue 分支无限重投")
    void malformedBodyGoesToDlqNotInfiniteRequeue() throws Exception {
        // 解析失败发生在 processOrderMessage 的失败计数之前：
        // 若按通用 Exception 分支 requeue=true，这条消息会永远回到队首刷满 CPU
        consumeBody("这不是 JSON{{{");

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(TAG, false, true);
    }

    private void consume() {
        consumeBody("{\"userId\":7,\"productId\":11,\"price\":9.90,\"shopId\":3}");
    }

    private void consumeBody(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("msg-001");
        // 带 userId、不带 amazonOrderId：走 processOrderMessage 分支（而非 SP-API 同步分支）
        Message message = new Message(body.getBytes(StandardCharsets.UTF_8), properties);
        consumer.onMessage(message, channel, TAG);
    }
}
