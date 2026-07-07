package com.amz.controller;

import com.amz.mapper.ProfitReportMapper;
import com.amz.model.ProfitReport;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 利润报告查询接口
 */
@RestController
@RequestMapping("/profit")
public class ProfitController {

    @Autowired
    private ProfitReportMapper profitReportMapper;

    /**
     * 查询某订单利润
     */
    @GetMapping("/order/{shopId}/{amazonOrderId}")
    public Result<List<ProfitReport>> getByOrder(@PathVariable Long shopId,
                                                 @PathVariable String amazonOrderId) {
        List<ProfitReport> list = profitReportMapper.selectList(new LambdaQueryWrapper<ProfitReport>()
                .eq(ProfitReport::getShopId, shopId)
                .eq(ProfitReport::getAmazonOrderId, amazonOrderId));
        return Result.success(list);
    }

    /**
     * 查询某 SKU 所有利润记录
     */
    @GetMapping("/sku/{shopId}/{sku}")
    public Result<List<ProfitReport>> getBySku(@PathVariable Long shopId,
                                               @PathVariable String sku) {
        List<ProfitReport> list = profitReportMapper.selectList(new LambdaQueryWrapper<ProfitReport>()
                .eq(ProfitReport::getShopId, shopId)
                .eq(ProfitReport::getSku, sku)
                .orderByDesc(ProfitReport::getStatDate));
        return Result.success(list);
    }

    /**
     * 月度汇总（按 SKU 维度）
     */
    @GetMapping("/summary/{shopId}")
    public Result<List<Map<String, Object>>> summary(@PathVariable Long shopId) {
        return Result.success(profitReportMapper.selectMonthlySummary(shopId));
    }
}
