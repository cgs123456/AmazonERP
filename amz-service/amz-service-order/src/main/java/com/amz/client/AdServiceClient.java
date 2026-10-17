package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.AdServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 广告服务 Feign 客户端（利润计算接入广告花费）。
 * <p>
 * 返回类型用 {@code Map} 避免跨模块依赖 amz-service-ad 的 AdReport。
 * 对应 AdController#getSummary，返回的 Map 含 cost（广告花费）等字段。
 */
@Component
@FeignClient(name = "amz-service-ad", fallbackFactory = AdServiceClientFallbackFactory.class)
public interface AdServiceClient {

    /**
     * 查询店铺整体广告汇总指标。
     *
     * @param shopId 店铺 ID
     * @param days   回看天数。<b>必须与利润分摊的分母窗口一致</b>：分母是近 30 天订单数
     *               （{@code ProfitCalculator#countRecentOrders}），分子就必须是近 30 天花费。
     *               §S33.82：此前不带 days，服务端按默认 7 天返回，于是 30 天的订单去摊
     *               7 天的钱——每条订单的广告费只有真实值的 7/30 ≈ 23%，净利高估。
     * @return 广告汇总（含 cost 广告花费字段）
     */
    @GetMapping("/ad/summary/{shopId}")
    Result<Map<String, Object>> getShopSummary(@PathVariable("shopId") Long shopId,
                                               @RequestParam("days") Integer days);
}
