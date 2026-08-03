package com.amz.controller;
import com.amz.annotation.ShopScoped;

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
 * 利润报告查询接口。
 * <p>
 * 类级映射使用 {@code /order/profit} 而非 {@code /profit}：网关仅配置了
 * {@code Path=/order/**} 路由到本服务，裸 {@code /profit} 前缀在网关侧无匹配路由，
 * 外部调用恒 404（前端 {@code /order/profit/summary/{shopId}} 即因此断链）。
 */
@RestController
@RequestMapping("/order/profit")
public class ProfitController {

    @Autowired
    private ProfitReportMapper profitReportMapper;

    /**
     * 查询某订单利润
     */
    @ShopScoped
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
    @ShopScoped
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
    @ShopScoped
    @GetMapping("/summary/{shopId}")
    public Result<List<Map<String, Object>>> summary(@PathVariable Long shopId) {
        return Result.success(profitReportMapper.selectMonthlySummary(shopId));
    }
}
