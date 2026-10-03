package com.amz.service.impl;

import com.amz.exception.OrderMessageRejectedException;
import com.amz.mapper.OrderAttributeMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.dto.OrderDto;
import com.amz.model.pojo.CustomAttribute;
import com.amz.model.pojo.Order;
import com.amz.model.pojo.OrderAttribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单金额的来源契约。
 * <p>
 * 动因：{@code saveOrderInternal} 为了拿一个价格，去跨服务查 {@code amz_product} 的旧列
 * （{@code ProductClient.getProductById}）。那张表在 7p 已证与实体漂移，端点改为入口拒绝，
 * 于是返回码永远是失败 —— <b>HTTP 下单与 MQ 消费两条入口其实都下不了单</b>，而这一点在
 * 只用 mock 的测试里完全看不出来。金额本来就在消息里（{@code OrderDto.price}，
 * {@code OrderConsumer} 也在解析它），所以正确的修法是用它，并把那条死依赖连同
 * {@code ProductClient}/{@code ProductClientFallbackFactory} 一起删掉。
 * <p>
 * 金额缺失/非正在旧代码里抛 {@code IllegalStateException}，会被失败计数当成瞬时故障一路重投；
 * 这里按「重投也不会变好」的 {@code OrderMessageRejectedException} 处理，并且必须在幂等占位
 * 之前拒掉，否则死信路由拿不到它。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("下单金额来源与永久不合法的处理")
class SaveOrderPriceSourceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderAttributeMapper orderAttributeMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private OrderServiceImpl service;

    /** MyBatis-Plus 的 ASSIGN_ID 会在 insert 时回填主键；mock 不回填，
     *  流程会死在「订单ID生成失败」上，断言根本走不到落库之后。这里把回填补上。
     */
    private void stubIdBackfill() {
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            inv.getArgument(0, Order.class).setId(555L);
            return 1;
        });
    }

    @BeforeEach
    void stubIdempotencyClaim() {
        // 只在真正走到业务分支的用例里需要；LenientSessionLockUtils 之外统一在此声明
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.lenient()
                .when(valueOperations.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
    }

    private static OrderDto dto(BigDecimal price) {
        OrderDto d = new OrderDto();
        d.setUserId(3);
        d.setProductId(7);
        d.setMessageId("m-1");
        d.setPrice(price);
        return d;
    }

    @Test
    @DisplayName("金额取自消息本身并原样落库")
    void finalPriceComesFromTheMessage() {
        stubIdBackfill();

        service.processOrderMessage(dto(new BigDecimal("59.98")));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(saved.capture());
        assertEquals(0, new BigDecimal("59.98").compareTo(saved.getValue().getFinalPrice()),
                "finalPrice 必须就是消息里的金额，实际=" + saved.getValue().getFinalPrice());
        assertEquals(7, saved.getValue().getProductId());
        assertEquals(3, saved.getValue().getUserId());
    }

    @Test
    @DisplayName("属性仍然逐条落 amz_order_attribute（label 走 name 列，7u 已对齐）")
    void attributesStillPersist() {
        OrderDto d = dto(new BigDecimal("10.00"));
        CustomAttribute attr = new CustomAttribute();
        attr.setLabel("颜色");
        attr.setValue(List.of("黑"));
        d.setSelectAttributes(List.of(attr));
        stubIdBackfill();

        service.processOrderMessage(d);

        ArgumentCaptor<OrderAttribute> row = ArgumentCaptor.forClass(OrderAttribute.class);
        verify(orderAttributeMapper).insert(row.capture());
        assertEquals("颜色", row.getValue().getLabel());
        assertEquals("黑", row.getValue().getValue());
    }

    @Test
    @DisplayName("金额缺失是永久不合法：直接拒，不落库也不占幂等位")
    void missingPriceIsRejectedNotRetried() {
        OrderDto d = dto(null);

        assertThrows(OrderMessageRejectedException.class, () -> service.processOrderMessage(d));
        verify(orderMapper, never()).insert(any(Order.class));
        verify(valueOperations, never()).setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("金额非正同样拒绝：0 与负数都不该变成一张 0 元订单")
    void nonPositivePriceIsRejected() {
        assertThrows(OrderMessageRejectedException.class,
                () -> service.processOrderMessage(dto(BigDecimal.ZERO)));
        assertThrows(OrderMessageRejectedException.class,
                () -> service.processOrderMessage(dto(new BigDecimal("-1.00"))));
        verify(orderMapper, never()).insert(any(Order.class));
    }
}
