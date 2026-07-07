package com.amz.service;

import com.amz.model.dto.OrderDto;
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
     * 根据用户ID获取订单列表
     * @param userId 用户ID
     * @return 订单列表
     */
    Result<List<Order>> getOrderListByUserId(Integer userId);
}
