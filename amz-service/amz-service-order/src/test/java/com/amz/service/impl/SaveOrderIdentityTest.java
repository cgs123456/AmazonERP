package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.OrderMessageRejectedException;
import com.amz.mapper.OrderAttributeMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.dto.OrderDto;
import com.amz.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单入口的归属、幂等键与「当场拒」契约。
 * <p>
 * 三条都是改之前会「看起来成功」的写法：
 * <ul>
 *   <li>{@code userId} 取自请求体，任何登录者都能给任意用户造订单；而
 *       {@code GET /order/getOrderList} 读的是认证上下文里的 userId，两边口径相反——
 *       自己下的单自己查不到，别人却能替你下；</li>
 *   <li>{@code messageId} 也取自请求体，消费端拿它做幂等占位：塞一个已占用的 id
 *       就能让一条订单被静默丢弃；</li>
 *   <li>金额非法照旧投递，页面回「已提交」，而消息只会进死信——订单永远不出现。</li>
 * </ul>
 * 金额的判定口径由 HTTP 入口与 MQ 入口共用（{@code priceRejectReason}），
 * 最后一条用例把「两处措辞相同」钉住：分叉成两套标准后，页面会出现
 * 「同一个数，网页拒我、队列收它」这种没法解释的差。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("下单入口：归属由认证上下文定，非法金额当场拒")
class SaveOrderIdentityTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderAttributeMapper orderAttributeMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    /** 真实序列化器：这条链路要验的就是消息体里到底写了什么，mock 掉等于什么都没测。 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderServiceImpl service;

    @BeforeEach
    void login() {
        UserContext.setUserId(42);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    private static OrderDto bodyFromClient(Long fakeUserId, String fakeMessageId, BigDecimal price) {
        OrderDto d = new OrderDto();
        d.setProductId(7);
        d.setPrice(price);
        if (fakeUserId != null) {
            d.setUserId(fakeUserId.intValue());
        }
        d.setMessageId(fakeMessageId);
        return d;
    }

    /** 从真正交给 RabbitTemplate 的消息里读出 JSON，断言的是投递内容而不是方法调用。 */
    private OrderDto publishedDto() {
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), sent.capture());
        String json = new String(sent.getValue().getBody(), StandardCharsets.UTF_8);
        try {
            return objectMapper.readValue(json, OrderDto.class);
        } catch (Exception e) {
            throw new AssertionError("投递的消息体不是合法订单 JSON：" + json, e);
        }
    }

    @Test
    @DisplayName("请求体里的 userId 与 messageId 一律作废：归属跟登录用户，幂等键由服务端生成")
    void identityAndIdempotencyKeyAreServerOwned() {
        Result<Void> res = service.saveOrder(bodyFromClient(99L, "already-claimed", new BigDecimal("19.90")));

        assertEquals(200, res.getCode());
        OrderDto published = publishedDto();
        assertEquals(42, published.getUserId(), "归属必须是登录用户，不能是请求体里那个 99");
        assertNotEquals("already-claimed", published.getMessageId(),
                "客户端指定的幂等键会撞已占用的槽位，必须换掉");
        assertNotNull(published.getMessageId());
        assertEquals(0, new BigDecimal("19.90").compareTo(published.getPrice()));
    }

    @Test
    @DisplayName("没有登录身份就不投递：这条入口不接受匿名下单")
    void anonymousIsRefusedBeforeAnythingIsSent() {
        UserContext.clear();

        Result<Void> res = service.saveOrder(bodyFromClient(99L, "m-1", new BigDecimal("19.90")));

        assertNotEquals(200, res.getCode());
        assertTrue(res.getMessage().contains("登录"), "拒绝理由要点名是身份问题，实际=" + res.getMessage());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("金额缺失或非正当场拒，不投一条注定进死信的消息再回「已提交」")
    void invalidPriceIsRefusedSynchronously() {
        Result<Void> missing = service.saveOrder(bodyFromClient(42L, "m-2", null));
        Result<Void> zero = service.saveOrder(bodyFromClient(42L, "m-3", BigDecimal.ZERO));
        Result<Void> negative = service.saveOrder(bodyFromClient(42L, "m-4", new BigDecimal("-1")));

        assertNotEquals(200, missing.getCode());
        assertNotEquals(200, zero.getCode());
        assertNotEquals(200, negative.getCode());
        assertTrue(zero.getMessage().contains("大于 0"), "实际=" + zero.getMessage());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("两个入口共用同一条金额口径：措辞必须一模一样")
    void bothEntriesShareThePriceRule() {
        Result<Void> httpRefusal = service.saveOrder(bodyFromClient(42L, "m-5", new BigDecimal("0.00")));

        OrderDto sameBody = bodyFromClient(42L, "m-5", new BigDecimal("0.00"));
        OrderMessageRejectedException mqRefusal =
                assertThrows(OrderMessageRejectedException.class, () -> service.processOrderMessage(sameBody));

        assertEquals(httpRefusal.getMessage(), mqRefusal.getMessage(),
                "HTTP 入口与 MQ 入口对同一个数给出不同理由，就是分叉成了两套标准");
        verify(orderMapper, never()).insert(any(com.amz.model.pojo.Order.class));
    }

    @Test
    @DisplayName("投递失败要带原因：MQ 不可达不能被洗成一句「保存订单失败」")
    void transportFailureReasonIsVisible() {
        org.mockito.Mockito.doThrow(new RuntimeException("Unable to connect to broker"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        Result<Void> res = service.saveOrder(bodyFromClient(42L, "m-6", new BigDecimal("5.00")));

        assertNotEquals(200, res.getCode());
        assertTrue(res.getMessage().contains("Unable to connect to broker"),
                "页面要能看出是投递失败，实际=" + res.getMessage());
    }
}
