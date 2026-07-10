package com.amz.agent.langchain4j;

import com.amz.agent.FunctionCall;
import com.amz.agent.ErpToolExecutor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * ERP 运营 Agent 工具集（LangChain4j @Tool 注解版）。
 * <p>
 * 通过 @Tool 注解声明工具，LangChain4j AiServices 会自动：
 * 1. 将工具 schema 推送给 LLM（原生 Function Calling，非文本 JSON 解析）
 * 2. LLM 返回 tool_calls 时自动反序列化参数并调用对应方法
 * 3. 将工具返回值注入对话继续推理
 * <p>
 * 本类是 ErpToolExecutor 的薄适配层，实际工具逻辑仍委托给 ErpToolExecutor，
 * 避免重复实现 12 个工具的业务逻辑。
 */
@Slf4j
@Component
public class ErpTools {

    @Autowired
    private ErpToolExecutor toolExecutor;

    // ===== 基础 5 工具（数据查询） =====

    @Tool("查询最近 N 天订单汇总，返回订单数量和总金额")
    String queryOrders(
            @P("店铺 ID") Long shopId,
            @P("查询天数，默认 7") Integer days) {
        log.info("LangChain4j 工具调用: query_orders shopId={} days={}", shopId, days);
        return toolExecutor.execute(buildCall("query_orders",
                Map.of("shopId", shopId, "days", days != null ? days : 7)));
    }

    @Tool("查询指定 SKU 的 FBA 库存和本地库存")
    String queryInventory(
            @P("店铺 ID") Long shopId,
            @P("卖家 SKU") String sku) {
        log.info("LangChain4j 工具调用: query_inventory shopId={} sku={}", shopId, sku);
        return toolExecutor.execute(buildCall("query_inventory",
                Map.of("shopId", shopId, "sku", sku)));
    }

    @Tool("查询销售额趋势，start/end 格式 yyyy-MM-dd")
    String querySales(
            @P("店铺 ID") Long shopId,
            @P("开始日期 yyyy-MM-dd") String start,
            @P("结束日期 yyyy-MM-dd") String end) {
        log.info("LangChain4j 工具调用: query_sales shopId={} start={} end={}", shopId, start, end);
        return toolExecutor.execute(buildCall("query_sales",
                Map.of("shopId", shopId, "start", start, "end", end)));
    }

    @Tool("查询单品利润分析，包括售价、成本、FBA 费用、利润率")
    String queryProfit(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin) {
        log.info("LangChain4j 工具调用: query_profit shopId={} asin={}", shopId, asin);
        return toolExecutor.execute(buildCall("query_profit",
                Map.of("shopId", shopId, "asin", asin)));
    }

    @Tool("智能补货建议，基于销量历史和库存计算补货量")
    String suggestReplenish(
            @P("店铺 ID") Long shopId,
            @P("卖家 SKU") String sku) {
        log.info("LangChain4j 工具调用: suggest_replenish shopId={} sku={}", shopId, sku);
        return toolExecutor.execute(buildCall("suggest_replenish",
                Map.of("shopId", shopId, "sku", sku)));
    }

    // ===== 扩展 7 工具（P0 模块联动 + AI 智能运营） =====

    @Tool("库存健康度分级，返回 URGENT/AT_RISK/HEALTHY/OVERSTOCK/STOCKOUT 分布")
    String checkInventoryHealth(
            @P("店铺 ID") Long shopId) {
        log.info("LangChain4j 工具调用: check_inventory_health shopId={}", shopId);
        return toolExecutor.execute(buildCall("check_inventory_health",
                Map.of("shopId", shopId)));
    }

    @Tool("跨站点 Listing 复制，将源 ASIN 翻译+换算+加价后提交到目标站点")
    String crossMarketplaceListing(
            @P("店铺 ID") Long shopId,
            @P("源 ASIN") String sourceAsin,
            @P("目标站点代码，如 A1PA6795UKMFR9（德国）") String targetMarketplace) {
        log.info("LangChain4j 工具调用: cross_marketplace_listing shopId={} asin={} target={}",
                shopId, sourceAsin, targetMarketplace);
        return toolExecutor.execute(buildCall("cross_marketplace_listing",
                Map.of("shopId", shopId, "sourceAsin", sourceAsin, "targetMarketplace", targetMarketplace)));
    }

    @Tool("广告 ACoS/ROAS 分析，ACoS = 广告花费 / 广告销售额")
    String analyzeAdPerformance(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin,
            @P("分析天数，默认 7") Integer days) {
        log.info("LangChain4j 工具调用: analyze_ad_performance shopId={} asin={} days={}",
                shopId, asin, days);
        return toolExecutor.execute(buildCall("analyze_ad_performance",
                Map.of("shopId", shopId, "asin", asin, "days", days != null ? days : 7)));
    }

    @Tool("竞品价格监控，返回竞品均价、最低价、Buy Box 价格和调价建议")
    String monitorCompetitorPrice(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin) {
        log.info("LangChain4j 工具调用: monitor_competitor_price shopId={} asin={}", shopId, asin);
        return toolExecutor.execute(buildCall("monitor_competitor_price",
                Map.of("shopId", shopId, "asin", asin)));
    }

    @Tool("FBA 费用预估，weight 单位 kg，sizeTier 为 standard 或 oversize")
    String estimateFbaFees(
            @P("店铺 ID") Long shopId,
            @P("卖家 SKU") String sku,
            @P("重量 kg") Double weight,
            @P("尺寸层级：standard 或 oversize") String sizeTier) {
        log.info("LangChain4j 工具调用: estimate_fba_fees shopId={} sku={} weight={} sizeTier={}",
                shopId, sku, weight, sizeTier);
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", shopId);
        args.put("sku", sku);
        args.put("weight", weight);
        args.put("sizeTier", sizeTier);
        return toolExecutor.execute(buildCall("estimate_fba_fees", args));
    }

    @Tool("多语种翻译，sourceLang/targetLang 如 en/de/fr/it/es/ja")
    String translateListing(
            @P("店铺 ID") Long shopId,
            @P("待翻译文本") String text,
            @P("源语言代码") String sourceLang,
            @P("目标语言代码") String targetLang) {
        log.info("LangChain4j 工具调用: translate_listing shopId={} {}→{}", shopId, sourceLang, targetLang);
        return toolExecutor.execute(buildCall("translate_listing",
                Map.of("shopId", shopId, "text", text, "sourceLang", sourceLang, "targetLang", targetLang)));
    }

    @Tool("AI 促销方案生成，goal 如 提升销量/清库存/新品冷启")
    String generatePromotionPlan(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin,
            @P("促销目标：提升销量/清库存/新品冷启") String goal) {
        log.info("LangChain4j 工具调用: generate_promotion_plan shopId={} asin={} goal={}",
                shopId, asin, goal);
        return toolExecutor.execute(buildCall("generate_promotion_plan",
                Map.of("shopId", shopId, "asin", asin, "goal", goal)));
    }

    // ===== 辅助方法 =====

    /**
     * 构建 FunctionCall 对象，委托给 ErpToolExecutor 执行。
     */
    private FunctionCall buildCall(String name, Map<String, Object> args) {
        FunctionCall call = new FunctionCall();
        call.setName(name);
        call.setArguments(args);
        return call;
    }
}
