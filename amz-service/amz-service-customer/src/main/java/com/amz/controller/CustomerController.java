package com.amz.controller;
import lombok.extern.slf4j.Slf4j;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;

import com.amz.model.CustomerTicket;
import com.amz.model.ReviewSolicitation;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客服工单 REST 端点。
 */
@RestController
@RequestMapping("/customer")
@Slf4j
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
    @ShopScoped
    @GetMapping("/ticket/list/{shopId}")
    public Result<List<CustomerTicket>> listTickets(
            @PathVariable Long shopId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return Result.paged(customerService.listTickets(
                shopId, status, category, PageRequest.of(size, cursor)));
    }

    /**
     * 批量发送索评请求。
     * POST /customer/review/solicit/{shopId}
     */
    @ShopScoped
    /**
     * 关闭工单（PENDING/REPLIED → CLOSED）。工单此前无终态，售后队列只进不出。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "客服工单", action = "关闭", description = "工单结案")
    @PostMapping("/ticket/{ticketId}/close")
    public Result<CustomerTicket> closeTicket(@PathVariable Long ticketId) {
        try {
            return Result.success(customerService.closeTicket(ticketId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("关闭工单失败 ticketId={}", ticketId, e);
            return Result.failure("关闭工单失败：" + e.getMessage());
        }
    }

    @PostMapping("/review/solicit/{shopId}")
    public Result<Integer> solicitReviews(@PathVariable Long shopId) {
        return Result.success(customerService.sendReviewSolicitations(shopId));
    }

    /**
     * 查询索评记录。
     * GET /customer/review/list/{shopId}
     */
    @ShopScoped
    @GetMapping("/review/list/{shopId}")
    public Result<List<ReviewSolicitation>> listSolicitations(
            @PathVariable Long shopId,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return Result.paged(customerService.listSolicitations(shopId, PageRequest.of(size, cursor)));
    }
}
