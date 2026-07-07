package com.amz.controller;

import com.amz.context.UserContext;
import com.amz.model.dto.OrderDto;
import com.amz.model.pojo.Order;
import com.amz.model.vo.OrderVo;
import com.amz.result.Result;
import com.amz.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/order")
public class OrderController {


    @Autowired
    private OrderService orderService;

    @PostMapping("/saveOrder")
    public Result<Void> saveOrder(@RequestBody OrderDto orderDto) {
        return orderService.saveOrder(orderDto);
    }

    @GetMapping("/getOrderList")
    public Result<List<Order>> getOrderList() {
        Integer userId = UserContext.getUserId();
        return orderService.getOrderListByUserId(userId);
    }
}
