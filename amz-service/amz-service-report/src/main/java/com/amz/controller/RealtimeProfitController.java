package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.CostAllocation;
import com.amz.model.ProfitSnapshot;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.RealtimeProfitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 实时利润核算 REST 端点。
 */
@RestController
@RequestMapping("/report/profit")
public class RealtimeProfitController {

    @Autowired
    private RealtimeProfitService realtimeProfitService;

    // ==================== 利润快照 ====================

    /** 触发 SKU 利润快照 */
    @ShopScoped
    @PostMapping("/snapshot")
    public Result<ProfitSnapshot> snapshot(@RequestParam Long shopId,
                                            @RequestParam String sku,
                                            @RequestParam(required = false) String asin) {
        return Result.success(realtimeProfitService.snapshotProfit(shopId, sku, asin));
    }

    /** 查询利润快照列表 */
    @ShopScoped
    @GetMapping("/snapshot/list/{shopId}")
    public Result<List<ProfitSnapshot>> listSnapshots(@PathVariable Long shopId,
                                                       @RequestParam(required = false) String sku,
                                                       @RequestParam(required = false) String startTime,
                                                       @RequestParam(required = false) String endTime,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String cursor) {
        return Result.paged(realtimeProfitService.listSnapshots(shopId, sku, startTime, endTime,
                PageRequest.of(size, cursor)));
    }

    /** 利润趋势（按小时） */
    @ShopScoped
    @GetMapping("/trend/{shopId}")
    public Result<Map<String, Object>> profitTrend(@PathVariable Long shopId,
                                                    @RequestParam String sku,
                                                    @RequestParam(required = false) String asin,
                                                    @RequestParam(defaultValue = "24") Integer hours) {
        return Result.success(realtimeProfitService.profitTrend(shopId, sku, asin, hours));
    }

    /** 利润汇总（按 SKU 维度） */
    @ShopScoped
    @GetMapping("/summary/{shopId}")
    public Result<Map<String, Object>> profitSummary(@PathVariable Long shopId,
                                                      @RequestParam(required = false) String startTime,
                                                      @RequestParam(required = false) String endTime) {
        return Result.success(realtimeProfitService.profitSummary(shopId, startTime, endTime));
    }

    // ==================== 费用分摊 ====================

    /** 保存费用分摊记录 */
    @ShopScoped
    @PostMapping("/allocation")
    public Result<CostAllocation> saveAllocation(@RequestBody CostAllocation allocation) {
        return Result.success(realtimeProfitService.saveAllocation(allocation));
    }

    /** 查询分摊记录 */
    @ShopScoped
    @GetMapping("/allocation/list/{shopId}")
    public Result<List<CostAllocation>> listAllocations(@PathVariable Long shopId,
                                                         @RequestParam(required = false) String costType,
                                                         @RequestParam(required = false) String startDate,
                                                         @RequestParam(required = false) String endDate,
                                                         @RequestParam(required = false) Integer size,
                                                         @RequestParam(required = false) String cursor) {
        return Result.paged(realtimeProfitService.listAllocations(shopId, costType, startDate, endDate,
                PageRequest.of(size, cursor)));
    }

    /** 执行成本分摊计算 */
    @ShopScoped
    @PostMapping("/allocate/{shopId}")
    public Result<Map<String, BigDecimal>> allocateCost(@PathVariable Long shopId,
                                                         @RequestParam String costType,
                                                         @RequestParam BigDecimal totalAmount,
                                                         @RequestBody List<String> skus) {
        return Result.success(realtimeProfitService.allocateCost(shopId, costType, totalAmount, skus));
    }
}
