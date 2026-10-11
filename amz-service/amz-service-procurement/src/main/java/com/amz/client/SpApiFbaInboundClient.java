package com.amz.client;

import com.amz.client.fallback.SpApiFbaInboundClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * SP-API FBA 入库计划客户端（procurement → spapi）。
 * <p>
 * <b>存在动因</b>：{@code fbaInbound} 的 45 条 operation 早已进统一目录与限流表，
 * 但采购侧的 FBA 货件业务层对它们<b>零调用</b>——补货建议算出来了，却落不成真实入库计划。
 * 本客户端补上这条接线：把补货建议转成 Amazon 侧的真实入库计划。
 * <p>
 * 走 spapi 的统一 operation 执行器（{@code POST /spapi/operations/{operationId}}），
 * 而不是在采购侧再造一个 SP-API 客户端——凭证、区域端点、限流、Outbox 都在 spapi 侧。
 * <p>
 * <b>降级</b>：spapi 不可用时返回失败，调用方据此标记「计划未创建」，
 * 不伪造一个本地计划 ID（那会让后续所有入库动作都指向一个 Amazon 不认识的对象）。
 */
@FeignClient(name = "amz-service-spapi", contextId = "spApiFbaInboundClient",
        fallbackFactory = SpApiFbaInboundClientFallbackFactory.class)
public interface SpApiFbaInboundClient {

    /**
     * 执行一条 SP-API operation。
     *
     * @param operationId 官方 operationId（如 {@code fbaInbound.createInboundPlan}）
     * @param request     shopId / marketplaceId / 路径参数 / body
     * @return Amazon 返回的响应（含 inboundPlanId 等）
     */
    @PostMapping("/spapi/operations/{operationId}")
    Result<Map<String, Object>> execute(@PathVariable("operationId") String operationId,
                                        @RequestBody Map<String, Object> request);
}
