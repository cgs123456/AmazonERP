package com.amz.client.feign;

import com.amz.client.feign.fallback.FinanceServiceFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 财务微服务 Feign 客户端。
 * <p>
 * 通过 Nacos 服务名 {@code amz-service-finance} 调用，用于报表聚合。
 */
@FeignClient(name = "amz-service-finance", contextId = "financeFeignClient", fallbackFactory = FinanceServiceFeignClientFallbackFactory.class)
public interface FinanceServiceFeignClient {

    /**
     * 查询店铺利润（CNY）。
     * 对应 GET /finance/profit/{shopId}?startDate=&endDate=
     */
    @GetMapping("/finance/profit/{shopId}")
    Map<String, Object> calculateProfit(@PathVariable("shopId") Long shopId,
                                        @RequestParam(value = "startDate", required = false) String startDate,
                                        @RequestParam(value = "endDate", required = false) String endDate);
}
