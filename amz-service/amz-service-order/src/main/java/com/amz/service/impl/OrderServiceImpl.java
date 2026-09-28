package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.client.FinanceServiceFeignClient;
import com.amz.client.ProductClient;
import com.amz.constant.MqConstant;
import com.amz.enums.OrderStatusEnum;
import com.amz.exception.MessageProcessLimitExceededException;
import com.amz.mapper.OrderAttributeMapper;
import com.amz.mapper.OrderItemMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.dto.OrderDto;
import com.amz.model.dto.OrderItemSyncDto;
import com.amz.model.dto.OrderSyncDto;
import com.amz.model.pojo.CustomAttribute;
import com.amz.model.pojo.Order;
import com.amz.model.pojo.OrderItem;
import com.amz.model.pojo.OrderAttribute;
import com.amz.model.pojo.Product;
import com.amz.result.Result;
import com.amz.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    /**
     * 凭证生成走 MQ（B1，默认 true）还是同步 Feign（false，回退）。
     * <p>
     * MQ 路径：Seata 全局事务只覆盖订单本地落库，不再跨服务持有锁；
     * finance 侧消费失败进 DLQ，generateOrderVoucher 自身幂等，重投安全。
     */
    @Value("${finance.voucher.async:true}")
    private boolean voucherAsyncEnabled;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

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

    /**
     * 发布凭证生成消息（B1：与 saveOrder 同一 JSON 文本消息范式）。
     * <p>
     * 注意：发送在 Seata 全局事务内，若事务最终回滚而消息已发出，
     * finance 侧会产生一条 sourceNo 指向不存在订单的孤儿凭证——
     * generateOrderVoucher 幂等保证重发安全，孤儿凭证可按 sourceNo 对账清理，
     * 与改造前（Feign 在事务内调用成功后回滚）窗口一致，未引入新风险。
     */
    private void publishVoucherMessage(OrderSyncDto syncDto) {
        try {
            // 契约单源：与消费方共用 amz-common 的 VoucherMessage，字段漂移编译期即暴露
            com.amz.mq.VoucherMessage payload = new com.amz.mq.VoucherMessage(
                    syncDto.getShopId(),
                    syncDto.getAmazonOrderId(),
                    syncDto.getTotalAmount(),
                    syncDto.getCurrency());
            String json = objectMapper.writeValueAsString(payload);
            Message message = MessageBuilder
                    .withBody(json.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                    .setMessageId(UUID.randomUUID().toString())
                    .build();
            rabbitTemplate.send(MqConstant.VOUCHER_EXCHANGE, MqConstant.VOUCHER_ROUTING_KEY, message);
            log.info("凭证生成消息已发送：amazonOrderId={}, shopId={}",
                    syncDto.getAmazonOrderId(), syncDto.getShopId());
        } catch (Exception e) {
            // 发送失败抛异常触发事务回滚 + 上游重试（幂等查重保证重入安全），
            // 禁止静默吞掉导致订单有、凭证永远无
            log.error("凭证生成消息发送失败：amazonOrderId={}", syncDto.getAmazonOrderId(), e);
            throw new IllegalStateException("凭证消息发送失败：amazonOrderId=" + syncDto.getAmazonOrderId(), e);
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

        // 幂等查重：必须带店铺（和站点）维度，不能只按 amazonOrderId 判重。
        //
        // 为什么改：amz_order 的唯一键已由 Flyway V4 收敛为
        // uk_shop_market_order (shop_id, marketplace_id, amazon_order_id)（spec P0-40）。
        // 在此之前只按 amazonOrderId 查重，等价于假设「订单号全局唯一」——一旦同一订单号
        // 出现在另一个店铺/站点，本店的订单会被判成「已存在的重复订单」而**静默丢弃**：
        // 日志写的是幂等跳过，界面上就是订单少了一张且没有任何报错。
        // 这是多租户 ERP 最难排查的一类缺陷（数据丢失 + 不可观测），因此查重条件必须与
        // 唯一键同构：shop_id + marketplace_id + amazon_order_id。
        LambdaQueryWrapper<Order> dedupQuery = new LambdaQueryWrapper<Order>()
                .eq(Order::getAmazonOrderId, syncDto.getAmazonOrderId());
        if (syncDto.getShopId() != null) {
            dedupQuery.eq(Order::getShopId, syncDto.getShopId());
        } else {
            // 缺 shopId 时绝不能退化成全局判重：宁可放行让唯一索引去兜底（重复会报
            // DuplicateKeyException，是可观测的失败），也不要静默丢单。
            log.warn("syncAmazonOrder 缺少 shopId，降级为不带店铺维度的查重：amazonOrderId={}",
                    syncDto.getAmazonOrderId());
        }
        if (syncDto.getMarketplaceId() != null && !syncDto.getMarketplaceId().isEmpty()) {
            dedupQuery.eq(Order::getMarketplaceId, syncDto.getMarketplaceId());
        }
        Long existCount = orderMapper.selectCount(dedupQuery);
        if (existCount != null && existCount > 0) {
            log.info("syncAmazonOrder 幂等跳过：shopId={}, marketplaceId={}, amazonOrderId={} 已存在",
                    syncDto.getShopId(), syncDto.getMarketplaceId(), syncDto.getAmazonOrderId());
            // 订单已存在时仍然补写明细行：首轮同步可能没拿到行级数据（orderItems 端点失败/限流），
            // 重投时若直接返回，明细会永久缺失且没有任何报错。幂等由 uk_order_item 保证。
            persistOrderItems(syncDto);
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
            persistOrderItems(syncDto);
            // 业财一体化：订单落库成功后触发凭证生成（借应收 / 贷收入 + 多币种换算）。
            // B1 默认走 MQ（finance 侧消费，失败进 DLQ；generateOrderVoucher 幂等，重投安全）。
            // 同步 Feign 仅在 finance.voucher.async=false 时作为回退保留。
            if (syncDto.getShopId() != null
                    && syncDto.getTotalAmount() != null
                    && syncDto.getCurrency() != null
                    && !syncDto.getCurrency().isEmpty()) {
                if (voucherAsyncEnabled) {
                    // M7：事务提交后再发送。事务内同步 send 一旦 DB 回滚，
                    // MQ 消息已发出会形成孤儿凭证；afterCommit 保证先落库后发消息。
                    // 无事务同步上下文（如单测直调）时直接发送。
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        try {
                                            publishVoucherMessage(syncDto);
                                        } catch (Exception e) {
                                            // 提交后已无法回滚：大声记录，由 DLQ/对账补发兜底
                                            log.error("凭证消息提交后发送失败，需对账补发：amazonOrderId={}",
                                                    syncDto.getAmazonOrderId(), e);
                                        }
                                    }
                                });
                    } else {
                        publishVoucherMessage(syncDto);
                    }
                } else {
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
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发场景下唯一索引兜底：另一线程已插入，视为幂等成功
            log.warn("syncAmazonOrder 并发幂等跳过：amazonOrderId={}", syncDto.getAmazonOrderId());
        }
    }

    /**
     * 落库订单明细行（V5 {@code amz_order_item}）。
     * <p>
     * 幂等键与表唯一键 {@code uk_order_item(shop_id, amazon_order_id, amazon_order_item_id)}
     * 同构：先查后写（存在则 update，不存在则 insert），并捕获并发下的 DuplicateKeyException。
     * 之所以不用“先删后插”：删除窗口内明细会短暂消失，且 delete+insert 会把 update_time
     * 全部刷新、掩盖真实变更时间，不利于对账。
     *
     * @param syncDto 订单同步 DTO，{@code orderItems} 为空时直接返回（不写占位行）
     */
    private void persistOrderItems(OrderSyncDto syncDto) {
        List<OrderItemSyncDto> items = syncDto.getOrderItems();
        if (items == null || items.isEmpty()) {
            return;
        }
        Long shopId = syncDto.getShopId();
        if (shopId == null) {
            // shop_id 是 NOT NULL：缺店铺维度时无法落明细。宁可显式告警并跳过，
            // 也绝不用 0 或默认值伪造归属——那会让明细挂到错误店铺且不可察觉。
            log.warn("订单明细落库跳过：缺少 shopId，amazonOrderId={}, itemCount={}",
                    syncDto.getAmazonOrderId(), items.size());
            return;
        }
        int saved = 0;
        for (OrderItemSyncDto item : items) {
            String itemId = item.getAmazonOrderItemId();
            if (itemId == null || itemId.isEmpty()) {
                log.warn("订单明细落库跳过一行：缺少 amazonOrderItemId，amazonOrderId={}",
                        syncDto.getAmazonOrderId());
                continue;
            }
            try {
                OrderItem entity = toOrderItem(syncDto, item, shopId);
                OrderItem existing = orderItemMapper.selectOne(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getShopId, shopId)
                        .eq(OrderItem::getAmazonOrderId, syncDto.getAmazonOrderId())
                        .eq(OrderItem::getAmazonOrderItemId, itemId));
                if (existing != null) {
                    entity.setId(existing.getId());
                    orderItemMapper.updateById(entity);
                } else {
                    orderItemMapper.insert(entity);
                }
                saved++;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.warn("订单明细并发幂等跳过：amazonOrderId={}, amazonOrderItemId={}",
                        syncDto.getAmazonOrderId(), itemId);
            }
        }
        log.info("订单明细落库：amazonOrderId={}, shopId={}, 成功行数={}/{}",
                syncDto.getAmazonOrderId(), shopId, saved, items.size());
    }

    /**
     * 明细行映射。币种/配送渠道缺省时回退订单级取值（同一订单内这两个值是订单级属性）。
     * product_id 暂不解析：ProductClient 只提供按 ID 查询，没有 SKU 反查接口，
     * 强行猜测映射会制造错误的商品关联，因此保持 NULL 并留待商品侧提供 SKU 查询。
     */
    private OrderItem toOrderItem(OrderSyncDto syncDto, OrderItemSyncDto item, Long shopId) {
        OrderItem entity = new OrderItem();
        entity.setShopId(shopId);
        entity.setMarketplaceId(syncDto.getMarketplaceId());
        entity.setAmazonOrderId(syncDto.getAmazonOrderId());
        entity.setAmazonOrderItemId(item.getAmazonOrderItemId());
        entity.setAsin(item.getAsin());
        entity.setSellerSku(item.getSellerSku());
        entity.setTitle(item.getTitle());
        entity.setQuantity(item.getQuantity() == null ? 0 : item.getQuantity());
        entity.setItemPrice(item.getItemPrice());
        entity.setItemTax(item.getItemTax());
        entity.setPromotionDiscount(item.getPromotionDiscount());
        entity.setCurrency(item.getCurrency() != null ? item.getCurrency() : syncDto.getCurrency());
        entity.setFulfillmentChannel(item.getFulfillmentChannel() != null
                ? item.getFulfillmentChannel() : syncDto.getFulfillmentChannel());
        return entity;
    }

    /**
     * 保存订单的内部方法（提取公共逻辑）
     */
    private void saveOrderInternal(OrderDto orderDto, Integer userId) {
        // 获取商品信息（注意：本方法运行在 @Transactional 内，Feign 降级/超时时快速失败，
        // 避免长时间占用 DB 连接；抛异常触发回滚，由 MQ 机制稍后重试）
        Result<Product> productResult = productClient.getProductById(orderDto.getProductId());
        if (productResult == null || productResult.getCode() != 200 || productResult.getData() == null) {
            throw new IllegalStateException("商品不存在或商品服务不可用");
        }
        Product product = productResult.getData();
        if (product.getPrice() == null) {
            throw new IllegalStateException("商品价格缺失");
        }

        // 保存订单
        Order order = new Order();
        order.setProductId(orderDto.getProductId());
        order.setUserId(userId);
        order.setStatus(OrderStatusEnum.DUE.getCode());
        // 使用商品实际价格（经 Double.toString 中转，避免二进制浮点直接转 BigDecimal 的精度误差）
        order.setFinalPrice(new BigDecimal(Double.toString(product.getPrice())));
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
