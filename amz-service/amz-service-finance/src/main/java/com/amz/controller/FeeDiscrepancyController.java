package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.FeeDiscrepancyScanReport;
import com.amz.dto.InboundShortageRequest;
import com.amz.model.FeeDiscrepancy;
import com.amz.result.Result;
import com.amz.service.FeeDiscrepancyService;
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

/**
 * 费用差异 / 短收候选接口（T06）。
 * <p>
 * 鉴权：只读 {@code @ShopScoped}；扫描、登记、排除均加 {@code @RequireRole}；
 * id 型端点的归属校验在服务层（统一「不存在或无权访问」返回）。
 */
@Slf4j
@RestController
@RequestMapping("/finance/discrepancy")
public class FeeDiscrepancyController {

    @Autowired
    private FeeDiscrepancyService feeDiscrepancyService;

    /**
     * 扫描费用差异（实际扣费 vs 费用预估）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/scan")
    public Result<FeeDiscrepancyScanReport> scan(@RequestParam Long shopId,
                                                 @RequestParam(required = false) String marketplaceId) {
        try {
            return Result.success(feeDiscrepancyService.scan(shopId, marketplaceId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("fee discrepancy scan failed shopId={}", shopId, e);
            return Result.failure("费用差异扫描失败：" + e.getMessage());
        }
    }

    /**
     * 登记入库短收候选。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/inbound-shortage")
    public Result<Long> intakeInboundShortage(@RequestParam Long shopId,
                                              @RequestBody InboundShortageRequest request) {
        try {
            Long id = feeDiscrepancyService.intakeInboundShortage(shopId, request);
            if (id == null) {
                return Result.failure("该货件该 SKU 的入库短收已登记，无需重复登记");
            }
            return Result.success(id);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("inbound shortage intake failed shopId={}", shopId, e);
            return Result.failure("入库短收登记失败：" + e.getMessage());
        }
    }

    /**
     * 查询候选列表。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<FeeDiscrepancy>> list(@PathVariable Long shopId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String type) {
        try {
            return Result.success(feeDiscrepancyService.list(shopId, status, type));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 查询单条候选详情。
     * <p>
     * 归属校验在服务层：id 型入参不带 shopId，改个数字就读他店数据是典型的越权读取。
     */
    @ShopScoped
    @GetMapping("/{id}")
    public Result<FeeDiscrepancy> detail(@PathVariable Long id, @RequestParam Long shopId) {
        FeeDiscrepancy d = feeDiscrepancyService.get(shopId, id);
        if (d == null) {
            return Result.failure("差异记录不存在或无权访问");
        }
        return Result.success(d);
    }

    /**
     * 排除候选（人工复核后不成立）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/{id}/dismiss")
    public Result<Boolean> dismiss(@PathVariable Long id, @RequestParam Long shopId) {
        try {
            boolean ok = feeDiscrepancyService.updateStatus(shopId, id,
                    FeeDiscrepancy.STATUS_DISMISSED);
            return ok ? Result.success(true) : Result.failure("差异记录不存在或无权访问");
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }
}
