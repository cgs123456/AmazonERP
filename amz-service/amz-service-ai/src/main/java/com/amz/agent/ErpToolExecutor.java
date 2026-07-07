package com.amz.agent;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP 运营 Agent 工具调度器。
 * 复用购物 Agent 的 Function Calling 编排架构，工具实现从"购物"改为"运营数据分析"。
 *
 * 工具清单（12 个）：
 * 基础 5 Tool：
 * 1. query_orders(shopId, days)               → 查询最近N天订单汇总
 * 2. query_inventory(shopId, sku)             → 查询FBA/本地库存
 * 3. query_sales(shopId, start, end)          → 查询销售额趋势
 * 4. query_profit(shopId, asin)               → 查询单品利润
 * 5. suggest_replenish(shopId, sku)           → 智能补货建议
 * 新增 7 Tool（P0 模块联动 + AI 智能运营）：
 * 6. check_inventory_health(shopId)           → 库存健康度分级（P0-1 输出）
 * 7. cross_marketplace_listing(shopId, sourceAsin, targetMarketplace) → 跨站点Listing复制（P0-2 输出）
 * 8. analyze_ad_performance(shopId, asin, days) → 广告 ACoS/ROAS 分析
 * 9. monitor_competitor_price(shopId, asin)   → 竞品价格监控
 * 10. estimate_fba_fees(shopId, sku, weight, sizeTier) → FBA 费用预估
 * 11. translate_listing(shopId, text, sourceLang, targetLang) → 多语种翻译
 * 12. generate_promotion_plan(shopId, asin, goal) → AI 促销方案生成
 */
@Slf4j
@Component
public class ErpToolExecutor {

    private final Gson gson = new Gson();

    /**
     * 执行工具调用
     * @param call LLM 返回的函数调用描述
     * @return JSON 格式结果 {"ok":true/false,"message":"...","data":...}
     */
    public String execute(FunctionCall call) {
        if (call == null || call.getName() == null) {
            return fail("无效的工具调用");
        }
        String name = call.getName().trim();
        Map<String, Object> args = call.getArguments() == null ? Map.of() : call.getArguments();

        return switch (name) {
            case "query_orders"              -> queryOrders(args);
            case "query_inventory"           -> queryInventory(args);
            case "query_sales"               -> querySales(args);
            case "query_profit"              -> queryProfit(args);
            case "suggest_replenish"         -> suggestReplenish(args);
            case "check_inventory_health"    -> checkInventoryHealth(args);
            case "cross_marketplace_listing" -> crossMarketplaceListing(args);
            case "analyze_ad_performance"    -> analyzeAdPerformance(args);
            case "monitor_competitor_price"  -> monitorCompetitorPrice(args);
            case "estimate_fba_fees"         -> estimateFbaFees(args);
            case "translate_listing"         -> translateListing(args);
            case "generate_promotion_plan"   -> generatePromotionPlan(args);
            default -> fail("未知工具：" + name);
        };
    }

    /**
     * 工具 1：查询最近 N 天订单汇总。
     * 实际实现：查 MySQL amz_order 表 WHERE shop_id=? AND purchase_date > NOW() - N days
     */
    private String queryOrders(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        Integer days = toInt(args.get("days"), 7);

        log.info("工具调用 query_orders shopId={} days={}", shopId, days);
        // TODO: 接入 OrderMapper 查询真实数据
        // 示例：SELECT COUNT(*), SUM(final_price) FROM amz_order WHERE shop_id=? AND purchase_date > ?
        return ok("查询到 23 个新订单，总金额 $1,234.56",
                Map.of("count", 23, "totalAmount", 1234.56, "shopId", shopId, "days", days));
    }

    /**
     * 工具 2：查询 FBA/本地库存。
     * 实际实现：查 SP-API FBA Inventory API 或本地库存表
     */
    private String queryInventory(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));

        log.info("工具调用 query_inventory shopId={} sku={}", shopId, sku);
        // TODO: 接入 InventoryClient 查询 FBA 库存
        return ok("SKU-" + (sku != null ? sku : "B001") + ": FBA库存 124 件，本地库存 30 件",
                Map.of("fbaInventory", 124, "localInventory", 30, "sku", sku));
    }

    /**
     * 工具 3：查询销售额趋势。
     */
    private String querySales(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String start = toStr(args.get("start"));
        String end = toStr(args.get("end"));

        log.info("工具调用 query_sales shopId={} start={} end={}", shopId, start, end);
        // TODO: 接入聚合查询
        return ok("近 7 天销售额趋势：日均 $890，环比增长 12%",
                Map.of("dailyAvg", 890, "growthRate", 0.12));
    }

    /**
     * 工具 4：查询单品利润。
     */
    private String queryProfit(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));

        log.info("工具调用 query_profit shopId={} asin={}", shopId, asin);
        // TODO: 接入利润核算（售价 - 成本 - FBA费用 - 佣金）
        return ok("ASIN " + asin + " 利润分析：售价 $29.99，成本 $12.50，FBA费用 $6.80，利润率 35.6%",
                Map.of("price", 29.99, "cost", 12.50, "fbaFee", 6.80, "profitMargin", 0.356));
    }

    /**
     * 工具 5：智能补货建议。
     */
    private String suggestReplenish(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));

        log.info("工具调用 suggest_replenish shopId={} sku={}", shopId, sku);
        // TODO: 结合日均销量 + 库存 + 采购周期计算补货量
        return ok("建议补货 SKU-" + sku + "：当前库存 30 件，日均销量 8 件，建议补货 200 件（25 天用量）",
                Map.of("currentStock", 30, "dailySales", 8, "suggestedQty", 200, "coverageDays", 25));
    }

    // ===== 新增 7 Tool（P0 模块联动 + AI 智能运营） =====

    /**
     * 工具 6：库存健康度分级（P0-1 输出）。
     * 联动 InventoryHealthAnalyzer，返回店铺下各健康度等级 SKU 数量分布。
     * 分级：URGENT(DOS≤7) / AT_RISK(7-14) / HEALTHY(14-60) / OVERSTOCK(>60) / STOCKOUT
     */
    private String checkInventoryHealth(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));

        log.info("工具调用 check_inventory_health shopId={}", shopId);
        // TODO: 接入 FbaInventoryMapper 按健康度等级 GROUP BY 统计
        Map<String, Object> distribution = new HashMap<>();
        distribution.put("URGENT", 3);
        distribution.put("AT_RISK", 7);
        distribution.put("HEALTHY", 45);
        distribution.put("OVERSTOCK", 5);
        distribution.put("STOCKOUT", 1);
        Map<String, Object> urgentSkus = new HashMap<>();
        urgentSkus.put("sku", "B08X4-001");
        urgentSkus.put("dos", 4.0);
        urgentSkus.put("available", 32);
        urgentSkus.put("dailySales", 8);
        return ok("店铺 " + shopId + " 库存健康度：3 个紧急(URGENT) + 7 个风险(AT_RISK) + 45 个健康 + 5 个滞销 + 1 个缺货",
                Map.of("distribution", distribution, "urgentSkus", List.of(urgentSkus)));
    }

    /**
     * 工具 7：跨站点 Listing 复制（P0-2 输出）。
     * 联动 ListingCopyService，将源 Listing 翻译+换算+加价后异步提交到目标站点。
     */
    private String crossMarketplaceListing(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sourceAsin = toStr(args.get("sourceAsin"));
        String targetMarketplace = toStr(args.get("targetMarketplace"));

        log.info("工具调用 cross_marketplace_listing shopId={} asin={} target={}",
                shopId, sourceAsin, targetMarketplace);
        // TODO: 接入 ListingCopyService.submitCopyTask 异步提交
        return ok("已创建跨站点复制任务：ASIN " + sourceAsin + " → " + targetMarketplace +
                        "（翻译: en→de，汇率: 1.08，加价: 20%），任务 ID: TASK-20260707-001",
                Map.of("taskId", "TASK-20260707-001",
                        "sourceAsin", sourceAsin,
                        "targetMarketplace", targetMarketplace,
                        "translation", "en→de",
                        "exchangeRate", 1.08,
                        "priceMarkup", 0.20,
                        "status", "PROCESSING"));
    }

    /**
     * 工具 8：广告 ACoS/ROAS 分析。
     * ACoS = Ad Spend / Ad Sales；ROAS = 1 / ACoS。
     */
    private String analyzeAdPerformance(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        Integer days = toInt(args.get("days"), 7);

        log.info("工具调用 analyze_ad_performance shopId={} asin={} days={}", shopId, asin, days);
        // TODO: 接入 SP-API Sponsored Products Report API
        double adSpend = 156.80;
        double adSales = 628.40;
        double acos = adSpend / adSales;
        double roas = adSales / adSpend;
        double conversionRate = 0.085;
        return ok(String.format("ASIN %s 近 %d 天广告表现：花费 $%.2f，广告销售额 $%.2f，ACOS=%.1f%%，ROAS=%.2f，转化率=%.1f%%",
                        asin, days, adSpend, adSales, acos * 100, roas, conversionRate * 100),
                Map.of("adSpend", adSpend,
                        "adSales", adSales,
                        "acos", acos,
                        "roas", roas,
                        "conversionRate", conversionRate,
                        "days", days));
    }

    /**
     * 工具 9：竞品价格监控。
     * 通过 Amazon Product Pricing API 拉取 Buy Box 价格。
     */
    private String monitorCompetitorPrice(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));

        log.info("工具调用 monitor_competitor_price shopId={} asin={}", shopId, asin);
        // TODO: 接入 SP-API Pricing API（/products/pricing/v0/competitive-pricing）
        double myPrice = 29.99;
        double avgCompetitorPrice = 28.50;
        double lowestCompetitor = 26.99;
        double buyBoxPrice = 27.99;
        String suggestion = myPrice > buyBoxPrice
                ? "建议降价至 $" + buyBoxPrice + " 以夺回 Buy Box"
                : "价格竞争力良好，维持当前定价";
        return ok(String.format("ASIN %s 竞品监控：本店 $%.2f，竞品均价 $%.2f，最低价 $%.2f，Buy Box $%.2f。%s",
                        asin, myPrice, avgCompetitorPrice, lowestCompetitor, buyBoxPrice, suggestion),
                Map.of("myPrice", myPrice,
                        "avgCompetitorPrice", avgCompetitorPrice,
                        "lowestCompetitor", lowestCompetitor,
                        "buyBoxPrice", buyBoxPrice,
                        "priceGap", myPrice - buyBoxPrice,
                        "suggestion", suggestion));
    }

    /**
     * 工具 10：FBA 费用预估。
     * 按 Size Tier + 重量 + 区域查表（amz_fba_fee_table）。
     */
    private String estimateFbaFees(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));
        Object weightObj = args.get("weight");
        double weight = weightObj instanceof Number ? ((Number) weightObj).doubleValue() : 0.5;
        String sizeTier = toStr(args.get("sizeTier"));
        if (sizeTier == null) sizeTier = "standard";

        log.info("工具调用 estimate_fba_fees shopId={} sku={} weight={} sizeTier={}",
                shopId, sku, weight, sizeTier);
        // TODO: 接入 FbaFeeTableMapper 查表计算
        double fulfillmentFee = "standard".equalsIgnoreCase(sizeTier) ? 3.22 : 5.45;
        double storageFee = weight * 0.87;  // 月度仓储费 $0.87/kg
        double totalFbaFee = fulfillmentFee + storageFee;
        return ok(String.format("SKU %s FBA 费用预估（%s, %.2f kg）：履约费 $%.2f + 仓储费 $%.2f = $%.2f",
                        sku, sizeTier, weight, fulfillmentFee, storageFee, totalFbaFee),
                Map.of("sku", sku,
                        "sizeTier", sizeTier,
                        "weight", weight,
                        "fulfillmentFee", fulfillmentFee,
                        "storageFee", storageFee,
                        "totalFbaFee", totalFbaFee));
    }

    /**
     * 工具 11：多语种翻译。
     * 联动 TranslationService 三级缓存（SHA-256 → MySQL → DeepSeek API）。
     */
    private String translateListing(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String text = toStr(args.get("text"));
        String sourceLang = toStr(args.get("sourceLang"));
        String targetLang = toStr(args.get("targetLang"));

        log.info("工具调用 translate_listing shopId={} source={} target={} textLen={}",
                shopId, sourceLang, targetLang, text == null ? 0 : text.length());
        // TODO: 接入 TranslationService.translate（三级缓存）
        String translated = "[翻译] " + text;  // 模拟翻译结果
        return ok(String.format("已将 %s 文本翻译为 %s（缓存命中：%s）",
                        sourceLang, targetLang, "MySQL"),
                Map.of("sourceText", text,
                        "translatedText", translated,
                        "sourceLang", sourceLang,
                        "targetLang", targetLang,
                        "cacheHit", "MySQL"));
    }

    /**
     * 工具 12：AI 促销方案生成。
     * 基于历史销量 + 季节性 + 促销日历，调用 LLM 生成促销方案。
     */
    private String generatePromotionPlan(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        String goal = toStr(args.get("goal"));
        if (goal == null) goal = "提升销量";

        log.info("工具调用 generate_promotion_plan shopId={} asin={} goal={}", shopId, asin, goal);
        // TODO: 调用 LLM 生成促销方案（结合 SalesHistory + SeasonalIndex + PromotionCalendar）
        Map<String, Object> plan = new HashMap<>();
        plan.put("promotionType", "Lightning Deal");
        plan.put("discountRate", 0.20);
        plan.put("duration", "2026-07-15 至 2026-07-17（48 小时）");
        plan.put("estimatedSalesUplift", "150%");
        plan.put("budget", "$500 广告预算");
        plan.put("strategy", "Prime 会员定向 8 折 + SP 广告加投 + 关联流量词竞价上调 30%");
        return ok(String.format("已为 ASIN %s 生成促销方案（目标：%s）：Lightning Deal 8 折，预计销量提升 150%%",
                        asin, goal),
                Map.of("asin", asin, "goal", goal, "plan", plan));
    }

    // ===== 工具方法 =====

    private String ok(String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("message", message);
        result.put("data", data == null ? "" : data);
        return gson.toJson(result);
    }

    private String fail(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", false);
        result.put("message", message);
        return gson.toJson(result);
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.valueOf(obj.toString()); } catch (Exception e) { return null; }
    }

    private Integer toInt(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.valueOf(obj.toString()); } catch (Exception e) { return defaultValue; }
    }

    private String toStr(Object obj) {
        return obj == null ? null : obj.toString();
    }
}
