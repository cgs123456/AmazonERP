package com.amz.order;

import com.amz.constant.MqConstant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("OrderUpsertPublisher：发送失败必须抛出，不得静默吞掉")
class OrderUpsertPublisherTest {

    private static final Long SHOP_ID = 7L;
    private static final String ORDER_ID = "123-1234567-1234567";

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final OrderUpsertPublisher publisher = new OrderUpsertPublisher(rabbitTemplate);

    @Test
    @DisplayName("发布成功：走 save 交换机，body 字段与消费端解析约定一致")
    void publishSendsToSaveExchange() {
        publisher.publish(message());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(MqConstant.SAVE_ORDER_EXCHANGE), eq(""), captor.capture());
        String json = new String(captor.getValue().getBody(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"amazonOrderId\":\"" + ORDER_ID + "\""), json);
        assertTrue(json.contains("\"source\":\"" + OrderUpsertMessage.SOURCE_NOTIFICATION + "\""), json);
        assertTrue(json.contains("buyerInfo"), json);
    }

    @Test
    @DisplayName("发送失败：抛异常（旧实现 catch 后只打日志 = MQ 故障静默丢单）")
    void sendFailureThrows() {
        doThrow(new IllegalStateException("broker down"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        OrderUpsertPublishException ex = assertThrows(OrderUpsertPublishException.class,
                () -> publisher.publish(message()));

        assertTrue(ex.getMessage().contains(MqConstant.SAVE_ORDER_EXCHANGE));
        assertEquals("broker down", ex.getCause().getMessage());
    }

    @Test
    @DisplayName("异常信息不得携带消息体：日志与告警里不能出现买家信息")
    void exceptionMessageCarriesNoPayload() {
        doThrow(new IllegalStateException("broker down"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));
        OrderUpsertMessage msg = new OrderUpsertMessage(SHOP_ID, "ATVPDKIKX0DER", "NA", ORDER_ID,
                "Shipped", null, LocalDateTime.now(), "MFN", "Std", "USD", "19.99",
                "Zhang San", LocalDateTime.now(), "ORDER_CHANGE", "k-1");

        OrderUpsertPublishException ex = assertThrows(OrderUpsertPublishException.class,
                () -> publisher.publish(msg));

        assertFalse(ex.getMessage().contains("Zhang San"), ex.getMessage());
        assertFalse(ex.getMessage().contains("19.99"), ex.getMessage());
    }

    @Test
    @DisplayName("sendJson 失败同样抛异常，并可带 routingKey 定位")
    void sendJsonFailureThrows() {
        doThrow(new IllegalStateException("broker down"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        OrderUpsertPublishException ex = assertThrows(OrderUpsertPublishException.class,
                () -> publisher.sendJson(MqConstant.PROFIT_EXCHANGE,
                        MqConstant.PROFIT_ROUTING_KEY, Map.of("shopId", SHOP_ID)));

        assertTrue(ex.getMessage().contains(MqConstant.PROFIT_ROUTING_KEY), ex.getMessage());
    }

    @Test
    @DisplayName("消息体契约：金额与时间为字符串，缺失字段为 null 而非字符串 null")
    void bodyMapContract() {
        Map<String, Object> body = message().toBodyMap();

        assertEquals(ORDER_ID, body.get("amazonOrderId"));
        assertEquals(SHOP_ID, body.get("shopId"));
        assertNull(body.get("orderTotal"), "金额缺失时必须是 null，不能是 \"null\" 字样");
        assertEquals(OrderUpsertMessage.SOURCE_NOTIFICATION, body.get("source"));
    }

    @Test
    @DisplayName("必填校验：shopId / amazonOrderId 缺失直接拒绝构造")
    void requiredFieldsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new OrderUpsertMessage(null, null, null,
                ORDER_ID, "Shipped", null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderUpsertMessage(SHOP_ID, null, null,
                "  ", "Shipped", null, null, null, null, null, null, null, null, null, null));
    }

    private static OrderUpsertMessage message() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 9, 26, 10, 30, 0);
        return new OrderUpsertMessage(SHOP_ID, "ATVPDKIKX0DER", "NA", ORDER_ID, "Shipped",
                null, eventTime, "MFN", "Std", null, null, null, eventTime, "ORDER_CHANGE",
                SHOP_ID + ":" + ORDER_ID + ":" + eventTime + ":ORDER_CHANGE");
    }
}