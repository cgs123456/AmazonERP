package com.amz.controller;

import com.amz.model.CustomerTicket;
import com.amz.model.ReviewSolicitation;
import com.amz.result.Result;
import com.amz.service.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 客服工单 REST 端点。
 */
@RestController
@RequestMapping("/customer")
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    /**
     * 接收买家消息（AI 自动分类）。
     * POST /customer/ticket
     */
    @PostMapping("/ticket")
    public Result<CustomerTicket> receiveMessage(@RequestBody CustomerTicket ticket) {
        return Result.success(customerService.receiveMessage(ticket));
    }

    /**
     * 客服回复工单。
     * POST /customer/ticket/{ticketId}/reply
     */
    @PostMapping("/ticket/{ticketId}/reply")
    public Result<CustomerTicket> replyTicket(@PathVariable Long ticketId, @RequestParam String reply) {
        return Result.success(customerService.replyTicket(ticketId, reply));
    }

    /**
     * 查询店铺工单列表。
     * GET /customer/ticket/list/{shopId}?status=&category=
     */
    @GetMapping("/ticket/list/{shopId}")
    public Result<List<CustomerTicket>> listTickets(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category) {
        return Result.success(customerService.listTickets(shopId, status, category));
    }

    /**
     * 批量发送索评请求。
     * POST /customer/review/solicit/{shopId}
     */
    @PostMapping("/review/solicit/{shopId}")
    public Result<Integer> solicitReviews(@PathVariable Long shopId) {
        return Result.success(customerService.sendReviewSolicitations(shopId));
    }

    /**
     * 查询索评记录。
     * GET /customer/review/list/{shopId}
     */
    @GetMapping("/review/list/{shopId}")
    public Result<List<ReviewSolicitation>> listSolicitations(@PathVariable Long shopId) {
        return Result.success(customerService.listSolicitations(shopId));
    }
}
