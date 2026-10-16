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
     * <p>
     * shopId 在请求体（{@code CustomerTicket.shopId}）里，{@code @ShopScoped} 切面读不到参数，
     * 租户归属由 service 层 {@code requireShopOnRow} 按行判定；角色门禁则由
     * {@code @RequireRole} 解析请求体里的 shopId 后按该店铺角色判定。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "客服工单", action = "建单", description = "接收买家消息并建单")
    @PostMapping("/ticket")
    public Result<CustomerTicket> receiveMessage(@RequestBody CustomerTicket ticket) {
        return Result.success(customerService.receiveMessage(ticket));
    }

    /**
     * 客服回复工单。
     * POST /customer/ticket/{ticketId}/reply
     * <p>
     * shopId 只在工单行上（不在参数、也不在请求体），角色只能按全局角色判定——
     * 工单本身可属于任一店铺，这里没有「本次操作面向哪个店」的可解析答案。
     * 租户归属由 service 层按行校验（要求该行 shopId 在当前用户授权列表内）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "客服工单", action = "回复", description = "客服回复工单")
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
     * 关闭工单（PENDING/REPLIED → CLOSED）。工单此前无终态，售后队列只进不出。
     * <p>
     * 与 replyTicket 同理：shopId 只在工单行上，角色按全局角色判定，
     * 店铺归属由 service 层按行校验。
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

    /**
     * 批量发送索评请求。
     * POST /customer/review/solicit/{shopId}
     * <p>
     * shopId 在路径里，两个切面都能解析到：{@code @ShopScoped} 校验它是否在授权列表内，
     * {@code @RequireRole} 按<b>该店铺</b>角色判定。
     * <p>
     * 此前本端点没有任何租户注解（上一行的 {@code @ShopScoped} 误挂到了 closeTicket 上，
     * 而 closeTicket 无 shopId 参数 → 切面解析不到 → 直接放行，两个端点实际都没被保护）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @OperLog(module = "客服工单", action = "索评", description = "批量发送索评请求")
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
