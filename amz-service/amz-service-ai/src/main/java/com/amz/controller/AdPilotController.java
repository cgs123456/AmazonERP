package com.amz.controller;

import com.amz.agent.ErpToolExecutor;
import com.amz.agent.FunctionCall;
import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 广告托管（P4.1，干跑默认）。
 * <p>
 * <b>两段语义</b>：{@code mode=DRY_RUN}（默认）只产出分析与建议，不触任何下游写通道；
 * {@code mode=EXECUTE} 才允许下发——且要求调用方显式传 {@code confirm=EXECUTE}
 * （前端二次确认），防止 LLM 产出被静默执行。
 *
 * <h2>§S33.21 修复：此前的实现没有调用任何工具链</h2>
 * 原实现只 {@code import} 了 {@code Result} / 注解 / {@code LinkedHashMap}，
 * **没有注入任何服务**，返回的 {@code analysis} 与 {@code execution} 是两段写死的说明文字：
 * <pre>
 *   plan.put("analysis", "由 Agent 工具链产出（analyze_ad_performance / ...）");
 *   plan.put("execution", execute ? "执行通道=ad 服务真实改价；..." : "干跑：未下发任何变更");
 * </pre>
 * 也就是说：无论传什么 shopId / campaignId，响应内容完全相同。文档里写着
 * 「执行通道经 ad 服务 {@code POST /ad/keyword/optimize}」，代码里没有这个调用。
 * <p>
 * <b>这不是「诚实边界」，是未完成的实现伪装成骨架</b>——它比返回假数据好一点
 * （没有编造数字），但同样是「看起来能用、实际什么都没做」，而调用方无法从响应
 * 区分这两种情况。现在改为真正调用 {@link ErpToolExecutor}：
 * <ul>
 *   <li>分析：{@code analyze_ad_performance}（真实广告报表 → 花费/销售额/ACoS/ROAS）</li>
 *   <li>建议：{@code optimize_ad_campaign}（同一份报表 + 阈值规则，产出逐活动建议）</li>
 * </ul>
 * 两个工具都走 Feign 调 ad 服务，失败时**如实透出失败原因**，不降级成假方案。
 * <p>
 * <b>EXECUTE 仍然不下发</b>：Amazon Ads 凭证未联调，真实改价会显式失败。
 * 与其假装下发，不如在响应里把「没有下发」写清楚——这条边界保留。
 */
@Slf4j
@RestController
@RequestMapping("/ai/ad-pilot")
public class AdPilotController {

    /** 下发二次确认口令。前端在用户点「确认执行」后带上，防止 LLM 产出被静默执行。 */
    private static final String EXECUTE_CONFIRM = "EXECUTE";

    @Autowired
    private ErpToolExecutor toolExecutor;

    /**
     * 托管循环：分析 → 建议 →（可选）执行。
     *
     * @param shopId     店铺
     * @param campaignId 广告活动（可选，缺省全店分析）
     * @param mode       DRY_RUN（默认）/ EXECUTE
     * @param confirm    EXECUTE 模式下必须等于 {@code EXECUTE}
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "AI广告托管", action = "托管", description = "AI 广告托管循环（干跑默认）")
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestParam Long shopId,
                                           @RequestParam(required = false) String campaignId,
                                           @RequestParam(defaultValue = "DRY_RUN") String mode,
                                           @RequestParam(required = false) String confirm) {
        boolean execute = EXECUTE_CONFIRM.equalsIgnoreCase(mode);
        if (execute && !EXECUTE_CONFIRM.equals(confirm)) {
            // 参数校验失败走 failure，不落到下面的 try —— 这是调用方的错，不是服务不可用
            return Result.failure("执行模式需要二次确认：请传 confirm=EXECUTE");
        }

        try {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("shopId", shopId);
            plan.put("campaignId", campaignId);
            plan.put("mode", execute ? "EXECUTE" : "DRY_RUN");

            // ---- 1. 分析（真实报表） ----
            Map<String, Object> analysisArgs = new LinkedHashMap<>();
            analysisArgs.put("shopId", shopId);
            analysisArgs.put("days", 7);
            ToolOutcome analysis = callTool("analyze_ad_performance", analysisArgs);
            plan.put("analysis", analysis.body);

            // ---- 2. 建议（同一份报表 + 阈值规则） ----
            Map<String, Object> suggestArgs = new LinkedHashMap<>();
            suggestArgs.put("shopId", shopId);
            if (campaignId != null && !campaignId.isBlank()) {
                suggestArgs.put("campaignId", campaignId);
            }
            ToolOutcome suggestions = callTool("optimize_ad_campaign", suggestArgs);
            plan.put("suggestions", suggestions.body);

            // ---- 3. 执行 ----
            if (!execute) {
                plan.put("execution", "干跑：未下发任何变更");
            } else if (!analysis.ok || !suggestions.ok) {
                // 分析都没成功就谈不上执行——把原因原样交回，不伪造一次「已执行」
                plan.put("execution", "未执行：分析或建议步骤失败（见 analysis / suggestions 的 error）");
            } else {
                plan.put("execution", "未下发：Amazon Ads 凭证尚未联调，真实改价会显式失败；"
                        + "本端点只产出方案，下发通道待凭证就绪后接入");
            }

            plan.put("honestBoundary", "分析数据来自广告报表（真实落库）；建议为阈值规则推导，"
                    + "非关键词级精细调优；任何情况下都不会伪造「已下发」");

            // 分析失败时不返回 200 —— 否则调用方会把「空方案」当成「没有可优化的活动」。
            // 失败也要带上 plan：运营需要看到「哪一步失败了、失败原因是什么」。
            if (!analysis.ok) {
                return new Result<>("广告分析失败：" + analysis.body, 400, plan);
            }
            return Result.success(plan);
        } catch (Exception e) {
            log.warn("AI 广告托管失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 调一个 Agent 工具并解析其 JSON 结果。
     *
     * <p>工具返回结构固定为 {@code {"ok":bool,"message":str,"data":...}}（见
     * {@code ErpToolSupport.ok/fail}）。这里把 message 与 data 原样交给调用方，
     * **不做二次包装**——运营看到的就是工具产出的原话。
     *
     * <p>{@code ok=false} 时把 message 收进 {@code error}，供上层决定是否整体失败。
     */
    private ToolOutcome callTool(String name, Map<String, Object> args) {
        FunctionCall call = new FunctionCall();
        call.setName(name);
        call.setArguments(args);
        String raw = toolExecutor.execute(call);
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            boolean ok = json.has("ok") && json.get("ok").getAsBoolean();
            com.google.gson.JsonElement message = json.get("message");
            String text = message == null || message.isJsonNull() ? "" : message.getAsString();
            return new ToolOutcome(ok, text);
        } catch (Exception parseError) {
            // 工具契约被破坏时如实报告，而不是吞掉
            log.warn("工具 {} 返回非 JSON：{}", name, raw);
            return new ToolOutcome(false, "工具返回格式异常：" + parseError.getMessage());
        }
    }

    /** 一次工具调用的结果：{@code ok} 供失败判断，{@code body} 是工具原话（给运营看）。 */
    private record ToolOutcome(boolean ok, String body) {
    }
}
