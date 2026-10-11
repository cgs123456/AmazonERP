package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.ReturnOrder;
import com.amz.result.Result;
import com.amz.service.impl.AmazonReturnSyncService;
import com.amz.service.impl.ReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 退货单接口（买家退货入仓）。
 * <p>
 * 补齐此前完全缺失的「货的那半条链」：退款只走财务域，实物退回没有任何落点，
 * 导致 COGS 不因退货冲减、库存不回补。收货时良品回补库存并写库存流水。
 * <p>
 * 鉴权：写操作 {@code @RequireRole OPERATOR/ADMIN}；{@code @ShopScoped} 覆盖带 shopId 的入参，
 * 以 id 为入参的端点（收货/结案/取消）归属校验落在服务层（LogisticsShopGuard）。
 */
@Slf4j
@RestController
@RequestMapping("/logistics/return")
public class ReturnOrderController {

    @Autowired
    private ReturnOrderService returnOrderService;

    @Autowired(required = false)
    private AmazonReturnSyncService amazonReturnSyncService;

    /**
     * 创建退货单。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "退货", action = "新增", description = "创建退货单（买家退货入仓）")
    @PostMapping
    public Result<ReturnOrder> create(@jakarta.validation.Valid @RequestBody ReturnOrder order) {
        try {
            return Result.success(returnOrderService.create(order));
        } catch (Exception e) {
            log.warn("创建退货单失败: {}", e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 收货：确定处置，良品回补库存并写流水。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "退货", action = "收货", description = "退货收货并按处置回补库存")
    @PostMapping("/{id}/receive")
    public Result<ReturnOrder> receive(@PathVariable Long id,
                                       @RequestParam Long warehouseId,
                                       @RequestParam String disposition) {
        try {
            return Result.success(returnOrderService.receive(id, warehouseId, disposition));
        } catch (Exception e) {
            log.warn("退货收货失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 结案（RECEIVED → CLOSED）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "退货", action = "结案", description = "退货单结案")
    @PostMapping("/{id}/close")
    public Result<ReturnOrder> close(@PathVariable Long id) {
        try {
            return Result.success(returnOrderService.close(id));
        } catch (Exception e) {
            log.warn("退货结案失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 取消（仅 PENDING）。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "退货", action = "取消", description = "取消退货单")
    @PostMapping("/{id}/cancel")
    public Result<ReturnOrder> cancel(@PathVariable Long id) {
        try {
            return Result.success(returnOrderService.cancel(id));
        } catch (Exception e) {
            log.warn("退货取消失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 退货单列表（按店铺，可选状态过滤）。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<ReturnOrder>> list(@PathVariable Long shopId,
                                          @RequestParam(required = false) String status) {
        try {
            return Result.success(returnOrderService.list(shopId, status));
        } catch (Exception e) {
            log.warn("查询退货单失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 已确认收货的良品退货件数（供财务侧冲减 COGS）。
     * <p>
     * 这是 finance→logistics 的第一条通道：退款闭环的「货」那一半要落到利润上，
     * 就必须有它。只统计 RECEIVED/CLOSED 且 disposition=GOOD 的单。
     */
    @ShopScoped
    @InternalServiceAccess("amz-service-finance")
    @GetMapping("/returned-units/{shopId}")
    public Result<Integer> returnedGoodUnits(@PathVariable Long shopId, @RequestParam String sku) {
        try {
            return Result.success(returnOrderService.returnedGoodUnits(shopId, sku));
        } catch (Exception e) {
            log.warn("查询退货件数失败 shopId={} sku={}: {}", shopId, sku, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 手动触发 Amazon FBA 客户退货同步（幂等：重复跑不会重复建单）。
     * <p>
     * 与定时调度共用同一服务。定时默认关闭，运营可用本端点按需拉取。
     * <b>仅 FBA</b>：SP-API 没有卖家自发货（FBM）退货报表，FBM 退货只能手工登记。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "退货", action = "同步", description = "从 Amazon 拉取 FBA 客户退货报表并导入")
    @PostMapping("/sync/{shopId}")
    public Result<Map<String, Object>> syncFromAmazon(@PathVariable Long shopId,
                                                      @RequestParam String marketplaceId,
                                                      @RequestParam(required = false) Integer windowDays) {
        try {
            return Result.success(amazonReturnSyncService
                    .syncShop(shopId, marketplaceId, windowDays == null ? 7 : windowDays)
                    .toMap());
        } catch (Exception e) {
            log.warn("Amazon 退货同步失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }
}
