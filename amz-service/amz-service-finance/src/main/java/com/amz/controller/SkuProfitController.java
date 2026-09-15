package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.dto.SkuProfitReport;
import com.amz.result.Result;
import com.amz.service.SkuProfitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单品真实利润接口（T09）。
 * <p>
 * 只读 + {@code @ShopScoped}（VIEWER 亦可查看）。
 */
@Slf4j
@RestController
@RequestMapping("/finance/profit")
public class SkuProfitController {

    @Autowired
    private SkuProfitService skuProfitService;

    /**
     * 单品真实利润（收入与费用取结算实际数，成本取采购批次）。
     *
     * @param sku 可选，指定单 SKU 时更省成本查询
     */
    @ShopScoped
    @GetMapping("/sku/{shopId}")
    public Result<SkuProfitReport> skuProfit(@PathVariable Long shopId,
                                             @RequestParam(required = false) String depositAfter,
                                             @RequestParam(required = false) String depositBefore,
                                             @RequestParam(required = false) String sku) {
        try {
            return Result.success(skuProfitService.compute(shopId, depositAfter, depositBefore, sku));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("sku profit compute failed shopId={}", shopId, e);
            return Result.failure("单品利润计算失败：" + e.getMessage());
        }
    }
}
