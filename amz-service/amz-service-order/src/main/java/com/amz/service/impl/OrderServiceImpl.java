package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.client.ProductClient;
import com.amz.constant.MqConstant;
import com.amz.enums.OrderStatusEnum;
import com.amz.mapper.OrderAttributeMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.dto.OrderDto;
import com.amz.model.pojo.CustomAttribute;
import com.amz.model.pojo.Order;
import com.amz.model.pojo.OrderAttribute;
import com.amz.model.pojo.Product;
import com.amz.result.Result;
import com.amz.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ProductClient productClient;

    @Autowired
    private OrderAttributeMapper orderAttributeMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result<Void> saveOrder(OrderDto orderDto) {
        // 库存扣减已由 ProductServiceImpl.buyProduct 通过 Lua 脚本完成，这里只做订单保存
        try {
            // 异步保存订单
            rabbitTemplate.convertAndSend(MqConstant.SAVE_ORDER_EXCHANGE, "", orderDto);
            return Result.success(null);
        } catch (Exception e) {
            log.error("保存订单失败", e);
            return Result.failure("保存订单失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processOrderMessage(OrderDto orderDto) {
        Integer userId = orderDto.getUserId();
        if (userId == null) {
            throw new IllegalStateException("用户ID不能为空");
        }

        // 幂等性检查：使用消息ID（消息ID在Consumer中已保证一定存在）
        String messageId = orderDto.getMessageId();
        
        // 使用Redis原子操作setIfAbsent防止并发处理（Redis单线程保证原子性）
        String processedKey = "order:message:processed:" + messageId;

        // setIfAbsent是原子操作：如果key不存在则设置并返回true，如果已存在则返回false
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(processedKey, "1", 30, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isNew)) {
            // key已存在，说明消息已处理过或正在处理中
            log.warn("消息已处理过或正在处理中，跳过处理，messageId: {}", messageId);
            throw new IllegalStateException("消息已处理过，messageId: " + messageId);
        }

        try {
            // 执行业务逻辑（订单保存）
            saveOrderInternal(orderDto, userId);

            // 订单保存成功，删除临时标记
            redisTemplate.delete(processedKey);

            log.info("消息已标记为已处理，messageId: {}", messageId);
        } catch (Exception e) {
            redisTemplate.delete(processedKey);
            throw e;
        }
    }

    /**
     * 保存订单的内部方法（提取公共逻辑）
     */
    private void saveOrderInternal(OrderDto orderDto, Integer userId) {
        // 获取商品信息
        Product product = productClient.getProductById(orderDto.getProductId()).getData();
        if (product == null) {
            throw new IllegalStateException("商品不存在");
        }
        
        // 保存订单
        Order order = new Order();
        order.setProductId(orderDto.getProductId());
        order.setUserId(userId);
        order.setStatus(OrderStatusEnum.DUE.getCode());
        // 使用商品实际价格
        order.setFinalPrice(java.math.BigDecimal.valueOf(product.getPrice()));
        orderMapper.insert(order);

        // 保存订单属性
        Long orderId = order.getId();
        if (orderId == null) {
            throw new IllegalStateException("订单ID生成失败");
        }

        List<CustomAttribute> selectAttributes = orderDto.getSelectAttributes();
        if (selectAttributes != null && !selectAttributes.isEmpty()) {
            for (CustomAttribute selectAttribute : selectAttributes) {
                if (selectAttribute == null) {
                    log.warn("订单属性为空，跳过");
                    continue;
                }

                List<String> values = selectAttribute.getValue();
                if (values == null || values.isEmpty()) {
                    log.warn("订单属性值为空，跳过属性：{}", selectAttribute.getLabel());
                    continue;
                }

                OrderAttribute orderAttribute = new OrderAttribute();
                orderAttribute.setOrderId(orderId);
                orderAttribute.setLabel(selectAttribute.getLabel());
                orderAttribute.setValue(values.get(0));
                orderAttributeMapper.insert(orderAttribute);
            }
        }
    }

    @Override
    public Result<List<Order>> getOrderListByUserId(Integer userId) {
        if (userId == null) {
            return Result.failure("用户ID不能为空");
        }

        LambdaQueryWrapper<Order> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Order::getUserId, userId);
        List<Order> orders = orderMapper.selectList(queryWrapper);

        return Result.success(orders);
    }
}