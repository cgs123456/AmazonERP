package com.amz.service;

import com.amz.model.dto.OrderDto;
import com.amz.model.dto.OrderSyncDto;
import com.amz.model.pojo.Order;
import com.amz.result.Result;

import java.util.List;

public interface OrderService {
    Result<Void> saveOrder(OrderDto orderDto);

    /**
     * 处理消息队列中的订单保存（带事务）
     * @param orderDto 订单DTO
     */
    void processOrderMessage(OrderDto orderDto);

    /**
     * 同步 Amazon SP-API 订单落库（幂等：按 amazonOrderId 查重，已存在则跳过）。
     * @param syncDto SP-API 同步订单数据载体
     */
    void syncAmazonOrder(OrderSyncDto syncDto);

    /**
     * 根据用户ID获取订单列表
     * @param userId 用户ID
     * @return 订单列表
     */
    Result<List<Order>> getOrderListByUserId(Integer userId);

    /**
     * 根据订单ID获取订单详情
     * @param orderId 订单ID
     * @return 订单详情
     */
    Result<Order> getOrderById(Long orderId);
}