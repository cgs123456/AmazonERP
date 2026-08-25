package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.client.FinanceServiceFeignClient;
import com.amz.client.ProductClient;
import com.amz.constant.MqConstant;
import com.amz.enums.OrderStatusEnum;
import com.amz.exception.MessageProcessLimitExceededException;
import com.amz.mapper.OrderAttributeMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.dto.OrderDto;
import com.amz.model.dto.OrderSyncDto;
import com.amz.model.pojo.CustomAttribute;
import com.amz.model.pojo.Order;
import com.amz.model.pojo.OrderAttribute;
import com.amz.model.pojo.Product;
import com.amz.result.Result;
import com.amz.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    /**
     * 单条消息最大处理尝试次数：超过后抛 MessageProcessLimitExceededException，
     * 由消费者转死信队列（nack requeue=false），避免无限重投。
     */
    private static final int MAX_PROCESS_ATTEMPTS = 3;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ProductClient productClient;

    @Autowired
    private FinanceServiceFeignClient financeServiceFeignClient;

    @Autowired
    private OrderAttributeMapper orderAttributeMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Result<Void> saveOrder(OrderDto orderDto) {
        // B2C 购物车下单场景：本方法仅负责发送订单保存消息，库存扣减应由调用方在调用前完成
        try {
            // 异步保存订单：发送 JSON 文本消息（与 OrderConsumer 原始字节解析对齐），
            // 同时设置 AMQP messageId，作为消费端缺少业务标识时的幂等 fallback。
            String json = objectMapper.writeValueAsString(orderDto);
            Message message = MessageBuilder
                    .withBody(json.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                    .setMessageId(UUID.randomUUID().toString())
                    .build();
            rabbitTemplate.send(MqConstant.SAVE_ORDER_EXCHANGE, "", message);
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

        // 幂等性检查：使用消息ID（由 Consumer 设置：优先 amazonOrderId+shopId，其次 AMQP messageId）
        String messageId = orderDto.getMessageId();

        // 使用Redis原子操作setIfAbsent防止并发/重复处理（Redis单线程保证原子性）
        String processedKey = "order:message:processed:" + messageId;
        // 失败次数计数 key：处理异常时递增，便于运维排查
        String failCountKey = "order:message:failed:" + messageId;

        // setIfAbsent 是原子操作：如果 key 不存在则设置并返回 true，如果已存在则返回 false
        // TTL 24 小时：覆盖 RabbitMQ 消息重投窗口，避免 deliveryTag 失效后重复落库
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(processedKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            // key 已存在，说明消息已处理成功过（失败时会释放占位），跳过重复处理
            log.warn("消息已处理过或正在处理中，跳过处理，messageId: {}", messageId);
            throw new IllegalStateException("消息已处理过，messageId: " + messageId);
        }

        try {
            // 执行业务逻辑（订单保存）
            saveOrderInternal(orderDto, userId);

            // 成功：保留 processedKey 作为 24h 幂等标记（不删除），防止 MQ 重投导致重复落库
            log.info("订单处理成功，messageId: {}", messageId);
        } catch (Exception e) {
            // 失败：释放幂等占位，允许 MQ 重投后重新处理。
            // （修复 at-most-once 丢单：旧实现失败后保留占位，重投消息被误判"已处理"而 ack，
            //   瞬时 DB 故障会导致订单丢失长达 TTL 窗口）
            redisTemplate.delete(processedKey);
            Long failCount = redisTemplate.opsForValue().increment(failCountKey);
            if (failCount != null && failCount == 1L) {
                redisTemplate.expire(failCountKey, 24, TimeUnit.HOURS);
            }
            log.error("订单处理失败，messageId: {}, 失败次数: {}", messageId, failCount, e);
            if (failCount != null && failCount >= MAX_PROCESS_ATTEMPTS) {
                // 连续失败达上限：抛专用异常由 Consumer 转死信队列（nack requeue=false），
                // 避免毒消息无限重投阻塞队列；死信可人工排查后补发
                throw new MessageProcessLimitExceededException(
                        "消息连续处理失败 " + failCount + " 次，转死信队列，messageId: " + messageId);
            }
            throw e;
        }
    }

    @Override
    @GlobalTransactional(timeoutMills = 300000, name = "amz-order-sync")
    @Transactional(rollbackFor = Exception.class)
    public void syncAmazonOrder(OrderSyncDto syncDto) {
        if (syncDto == null || syncDto.getAmazonOrderId() == null || syncDto.getAmazonOrderId().isEmpty()) {
            log.warn("syncAmazonOrder 跳过：amazonOrderId 为空，syncDto={}", syncDto);
            return;
        }

        // 幂等查重：按 amazonOrderId 查询（amz_order.uk_amazon_order 唯一索引保证）
        Long existCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getAmazonOrderId, syncDto.getAmazonOrderId()));
        if (existCount != null && existCount > 0) {
            log.info("syncAmazonOrder 幂等跳过：amazonOrderId={} 已存在", syncDto.getAmazonOrderId());
            return;
        }

        // 构造订单并落库
        Order order = new Order();
        order.setAmazonOrderId(syncDto.getAmazonOrderId());
        order.setShopId(syncDto.getShopId());
        order.setMarketplaceId(syncDto.getMarketplaceId());
        order.setOrderStatus(syncDto.getOrderStatus());
        order.setBuyerName(syncDto.getBuyerName());
        order.setPurchaseDate(syncDto.getPurchaseDate());
        order.setLastUpdateDate(syncDto.getLastUpdateDate());
        order.setFulfillmentChannel(syncDto.getFulfillmentChannel());
        order.setShipServiceLevel(syncDto.getShipServiceLevel());
        // 订单总金额映射到 final_price 字段（订单级金额）
        order.setFinalPrice(syncDto.getTotalAmount());
        // sync_status=1 表示已同步本地
        order.setSyncStatus(1);

        try {
            orderMapper.insert(order);
            log.info("syncAmazonOrder 落库成功：amazonOrderId={}, shopId={}",
                    syncDto.getAmazonOrderId(), syncDto.getShopId());
            // 业财一体化：订单落库成功后触发凭证生成（借应收 / 贷收入 + 多币种换算）。
            // 失败降级 warn 日志，不阻断订单落库主流程；凭证可后续按订单号补生成。
            if (syncDto.getShopId() != null
                    && syncDto.getTotalAmount() != null
                    && syncDto.getCurrency() != null
                    && !syncDto.getCurrency().isEmpty()) {
                try {
                    financeServiceFeignClient.generateOrderVoucher(
                            syncDto.getShopId(),
                            syncDto.getAmazonOrderId(),
                            syncDto.getTotalAmount(),
                            syncDto.getCurrency());
                } catch (Exception feignEx) {
                    log.warn("业财一体化凭证生成失败（不阻断订单落库）：amazonOrderId={}, shopId={}",
                            syncDto.getAmazonOrderId(), syncDto.getShopId(), feignEx);
                }
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发场景下唯一索引兜底：另一线程已插入，视为幂等成功
            log.warn("syncAmazonOrder 并发幂等跳过：amazonOrderId={}", syncDto.getAmazonOrderId());
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

    @Override
    public Result<Order> getOrderById(Long orderId) {
        if (orderId == null) {
            return Result.failure("订单ID不能为空");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return Result.failure("订单不存在：id=" + orderId);
        }
        return Result.success(order);
    }
}