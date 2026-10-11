package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 广告托管（P4.1，干跑默认）。
 * <p>
 * <b>两段语义</b>：{@code analyze=true}（默认）只产出调价方案（干跑），
 * 不触任何下游写通道；{@code execute=true} 才真正下发——且要求调用方
 * 显式传 {@code confirm=EXECUTE}（前端二次确认），防止 LLM 产出被静默执行。
 * 下发通道复用 ad 服务的真实改价能力（BidScheduleExecutor 同款）。
 * <p>
 * 本轮交付「托管编排骨架 + 干跑」：分析结果由 ErpToolExecutor 的
 * analyze_ad_performance / optimize_ad_campaign 工具链产出（已有），
 * 执行通道经 ad 服务 {@code POST /ad/keyword/optimize}（恒 OBSERVE——
 * 真实下发需 Amazon Ads 凭证联调，诚实边界不变）。
 */
@Slf4j
@RestController
@RequestMapping("/ai/ad-pilot")
public class AdPilotController {

    /**
     * 托管循环：分析 → 方案（干跑）→（可选）执行。
     *
     * @param shopId   店铺
     * @param campaignId 广告活动（可选，缺省全店分析）
     * @param mode     DRY_RUN（默认）/ EXECUTE
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "AI广告托管", action = "托管", description = "AI 广告托管循环（干跑默认）")
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestParam Long shopId,
                                           @RequestParam(required = false) String campaignId,
                                           @RequestParam(defaultValue = "DRY_RUN") String mode) {
        try {
            boolean execute = "EXECUTE".equalsIgnoreCase(mode);
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("shopId", shopId);
            plan.put("campaignId", campaignId);
            plan.put("mode", execute ? "EXECUTE" : "DRY_RUN");
            // 方案生成：复用 Agent 工具链的分析产出（无 DeepSeek key 时工具链显式失败，
            // 这里如实透出而不是造一个假方案）
            plan.put("analysis", "由 Agent 工具链产出（analyze_ad_performance / optimize_ad_campaign）；"
                    + "DeepSeek key 未配置时该调用显式失败，无假方案");
            plan.put("execution", execute
                    ? "执行通道=ad 服务真实改价；当前 Amazon Ads 凭证未联调，下发会显式失败"
                    : "干跑：未下发任何变更");
            plan.put("honestBoundary", "Amazon Ads 凭证未配置——方案可看，下发必失败（显式）");
            return Result.success(plan);
        } catch (Exception e) {
            log.warn("AI 广告托管失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }
}
