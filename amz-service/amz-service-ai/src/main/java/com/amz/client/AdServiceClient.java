package com.amz.client;

import com.amz.result.Result;
import com.amz.client.fallback.AdServiceClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 广告微服务 Feign 客户端。
 * 用于 Agent 工具调用广告报表与竞品监控。
 */
@FeignClient(name = "amz-service-ad", contextId = "adServiceClient", fallbackFactory = AdServiceClientFallbackFactory.class)
public interface AdServiceClient {

    /**
     * 查询店铺广告报表（含 ACoS/ROAS）。
     */
    @GetMapping("/ad/reports")
    Result<List<Map<String, Object>>> getReports(@RequestParam("shopId") Long shopId);

    /**
     * 查询竞品价格监控。
     */
    @GetMapping("/ad/competitor")
    Result<Map<String, Object>> getCompetitor(@RequestParam("shopId") Long shopId,
                                              @RequestParam("asin") String asin);

    /**
     * 搜索词综合分析（真实数据，供 Agent 工具 20 使用）。
     */
    @GetMapping("/ad/search-term/analyze/{shopId}")
    Result<Map<String, Object>> analyzeSearchTerms(@PathVariable("shopId") Long shopId,
                                                  @RequestParam(value = "campaignId", required = false) String campaignId,
                                                  @RequestParam(value = "days", required = false) Integer days);
}
