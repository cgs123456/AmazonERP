package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.ReportServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 报表微服务 Feign 客户端。
 * 用于 Agent 工具调用销售额趋势等真实聚合数据（供工具 21 使用）。
 */
@FeignClient(name = "amz-service-report", contextId = "reportServiceClient", fallbackFactory = ReportServiceClientFallbackFactory.class)
public interface ReportServiceClient {

    /**
     * 销售额趋势（按日聚合，供趋势分析）。
     * GET /report/dashboard/sales-trend?shopId=&days=
     */
    @GetMapping("/report/dashboard/sales-trend")
    Result<List<Map<String, Object>>> getSalesTrend(@RequestParam(value = "shopId", required = false) Long shopId,
                                                    @RequestParam(value = "days", defaultValue = "7") Integer days);
}
