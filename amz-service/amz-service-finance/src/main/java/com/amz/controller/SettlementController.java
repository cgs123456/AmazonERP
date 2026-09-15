package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.SettlementIngestReport;
import com.amz.model.SettlementDetail;
import com.amz.result.Result;
import com.amz.service.SettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结算原表接入接口（T04）。
 * <p>
 * 鉴权分工：只读加 {@code @ShopScoped}，写操作再加 {@code @RequireRole}（与物流模块口径一致）。
 */
@Slf4j
@RestController
@RequestMapping("/finance/settlement")
public class SettlementController {

    @Autowired
    private SettlementService settlementService;

    /**
     * 手动拉取结算原表并落库（拉取 → 解析 → 幂等落库 → 返回导入报告）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/sync")
    public Result<SettlementIngestReport> sync(@RequestParam Long shopId,
                                               @RequestParam(required = false) String marketplaceId,
                                               @RequestParam(required = false) String dataStartTime,
                                               @RequestParam(required = false) String dataEndTime) {
        try {
            return Result.success(settlementService.sync(shopId, marketplaceId, dataStartTime, dataEndTime));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("settlement sync failed shopId={}", shopId, e);
            return Result.failure("结算同步失败：" + e.getMessage());
        }
    }

    /**
     * 查询结算明细（可按订单号过滤）。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<SettlementDetail>> list(@PathVariable Long shopId,
                                               @RequestParam(required = false) String orderId) {
        try {
            return Result.success(settlementService.list(shopId, orderId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }
}
