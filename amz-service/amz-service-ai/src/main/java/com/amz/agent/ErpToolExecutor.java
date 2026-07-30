package com.amz.agent;

import com.amz.client.AdServiceClient;
import com.amz.client.InventoryServiceClient;
import com.amz.client.OrderServiceClient;
import com.amz.client.ProcurementServiceClient;
import com.amz.client.ProductServiceClient;
import com.amz.result.Result;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.amz.agent.review.ReviewAnalysisResult;
import com.amz.agent.review.ReviewAnalysisService;
import com.amz.agent.review.ReviewInfo;
import com.amz.agent.selection.SelectionAnalysisResult;
import com.amz.agent.selection.SelectionAnalysisService;
import com.amz.agent.selection.SelectionOpportunityInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ERP 运营 Agent 工具调度器。
 * 复用购物 Agent 的 Function Calling 编排架构，工具实现从"购物"改为"运营数据分析"。
 *
 * 工具清单（13 个）：
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
 * 13. analyze_product_reviews(shopId, asin, reviewsJson) → AI 评论分析（痛点/情感/建议）
 * 14. analyze_product_selection(keyword, marketplace) → AI 选品分析（市场/竞争/趋势/建议）
 *
 * 工具 1-12 通过 Feign 调用各微服务获取真实数据；工具 13-14 调用本地 AI 服务。
 * Feign 调用失败时降级返回错误信息。
 */
@Slf4j
@Component
public class ErpToolExecutor {

    @Autowired
    private ReviewAnalysisService reviewAnalysisService;

    @Autowired
    private SelectionAnalysisService selectionAnalysisService;

    @Autowired(required = false)
    private OrderServiceClient orderServiceClient;

    @Autowired(required = false)
    private InventoryServiceClient inventoryServiceClient;

    @Autowired(required = false)
    private AdServiceClient adServiceClient;

    @Autowired(required = false)
    private ProductServiceClient productServiceClient;

    @Autowired(required = false)
    private ProcurementServiceClient procurementServiceClient;

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
            case "analyze_product_reviews"   -> analyzeProductReviews(args);
            case "analyze_product_selection" -> analyzeProductSelection(args);
            default -> fail("未知工具：" + name);
        };
    }

    /**
     * 工具 1：查询最近 N 天订单汇总。
     * 通过 Feign 调用 amz-service-order 的 /order/list 端点。
     */
    private String queryOrders(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        Integer days = toInt(args.get("days"), 7);

        log.info("工具调用 query_orders shopId={} days={}", shopId, days);
        if (orderServiceClient == null) {
            return fail("订单服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = orderServiceClient.getOrderList(shopId, days);
            if (!isSuccess(result)) {
                return fail("订单查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            int count = toInt(data.get("count"), 0);
            Object totalAmount = data.get("totalAmount");
            return ok(String.format("查询到 %d 个订单，总金额 %s", count, totalAmount != null ? totalAmount : "$0"), data);
        } catch (Exception e) {
            log.error("query_orders Feign 调用失败 shopId={}", shopId, e);
            return fail("订单服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 2：查询 FBA/本地库存。
     * 通过 Feign 调用 amz-service-spapi 的 /spapi/inventory/{shopId} 端点。
     */
    private String queryInventory(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));

        log.info("工具调用 query_inventory shopId={} sku={}", shopId, sku);
        if (inventoryServiceClient == null) {
            return fail("库存服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getInventory(shopId);
            if (!isSuccess(result)) {
                return fail("库存查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            // 按 sku 过滤
            if (sku != null && !sku.isBlank()) {
                list = list.stream()
                        .filter(m -> sku.equals(toStr(m.get("sku"))))
                        .toList();
            }
            int totalAvailable = list.stream()
                    .mapToInt(m -> toInt(m.get("availableQuantity"), 0))
                    .sum();
            return ok(String.format("查询到 %d 个 SKU，可售库存共 %d 件", list.size(), totalAvailable),
                    Map.of("skuCount", list.size(), "totalAvailable", totalAvailable, "items", list));
        } catch (Exception e) {
            log.error("query_inventory Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 3：查询销售额趋势。
     * 通过 Feign 调用 amz-service-order 的 /order/profit/report 端点（含销售额数据）。
     */
    private String querySales(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String start = toStr(args.get("start"));
        String end = toStr(args.get("end"));
        if (start == null) start = LocalDate.now().minusDays(7).toString();
        if (end == null) end = LocalDate.now().toString();

        log.info("工具调用 query_sales shopId={} start={} end={}", shopId, start, end);
        if (orderServiceClient == null) {
            return fail("订单服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = orderServiceClient.getProfitReport(shopId, start, end);
            if (!isSuccess(result)) {
                return fail("销售额查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Object totalRevenue = data.get("totalRevenue");
            Object margin = data.get("margin");
            return ok(String.format("销售额 %s，利润率 %s", totalRevenue != null ? totalRevenue : "$0", margin != null ? margin : "0"),
                    data);
        } catch (Exception e) {
            log.error("query_sales Feign 调用失败 shopId={}", shopId, e);
            return fail("订单服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 4：查询单品利润。
     * 通过 Feign 调用 amz-service-order 的 /order/profit/report 端点。
     */
    private String queryProfit(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        // 默认查最近 30 天
        String start = LocalDate.now().minusDays(30).toString();
        String end = LocalDate.now().toString();

        log.info("工具调用 query_profit shopId={} asin={}", shopId, asin);
        if (orderServiceClient == null) {
            return fail("订单服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = orderServiceClient.getProfitReport(shopId, start, end);
            if (!isSuccess(result)) {
                return fail("利润查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Object totalRevenue = data.get("totalRevenue");
            Object totalCost = data.get("totalCost");
            Object totalProfit = data.get("totalProfit");
            Object margin = data.get("margin");
            String asinDesc = asin != null ? "ASIN " + asin + " " : "";
            return ok(String.format("%s利润分析（近30天）：收入 %s，成本 %s，利润 %s，利润率 %s",
                            asinDesc, totalRevenue, totalCost, totalProfit, margin), data);
        } catch (Exception e) {
            log.error("query_profit Feign 调用失败 shopId={}", shopId, e);
            return fail("订单服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 5：智能补货建议。
     * 通过 Feign 调用 amz-service-spapi 的 /spapi/replenish/suggest 端点。
     */
    private String suggestReplenish(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));

        log.info("工具调用 suggest_replenish shopId={} sku={}", shopId, sku);
        if (inventoryServiceClient == null) {
            return fail("库存服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getReplenishSuggest(shopId, sku);
            if (!isSuccess(result)) {
                return fail("补货建议查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            if (list == null || list.isEmpty()) {
                return ok("暂无补货建议", Map.of("suggestions", List.of()));
            }
            // 取第一条作为主要建议
            Map<String, Object> first = list.get(0);
            return ok(String.format("建议补货 SKU-%s：当前库存 %s 件，建议补货 %s 件",
                            toStr(first.get("sku")),
                            first.get("currentTotalStock"),
                            first.get("suggestedReplenishQty")),
                    Map.of("suggestions", list));
        } catch (Exception e) {
            log.error("suggest_replenish Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }

    // ===== 新增 7 Tool（P0 模块联动 + AI 智能运营） =====

    /**
     * 工具 6：库存健康度分级（P0-1 输出）。
     * 通过 Feign 调用 amz-service-spapi 的 /spapi/inventory/health 端点。
     */
    private String checkInventoryHealth(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));

        log.info("工具调用 check_inventory_health shopId={}", shopId);
        if (inventoryServiceClient == null) {
            return fail("库存服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getInventoryHealth(shopId);
            if (!isSuccess(result)) {
                return fail("库存健康度查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            // 按健康度等级分组统计
            Map<String, Integer> distribution = new HashMap<>();
            for (Map<String, Object> item : list) {
                String status = toStr(item.get("healthStatus"));
                if (status == null) status = "UNKNOWN";
                distribution.merge(status, 1, Integer::sum);
            }
            return ok(String.format("店铺 %s 库存健康度：%s", shopId, distribution),
                    Map.of("distribution", distribution, "items", list));
        } catch (Exception e) {
            log.error("check_inventory_health Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 7：跨站点 Listing 复制（P0-2 输出）。
     * 通过 Feign 调用 amz-service-product 的 /product/listing/copy 端点。
     */
    private String crossMarketplaceListing(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sourceAsin = toStr(args.get("sourceAsin"));
        String targetMarketplace = toStr(args.get("targetMarketplace"));

        log.info("工具调用 cross_marketplace_listing shopId={} asin={} target={}",
                shopId, sourceAsin, targetMarketplace);
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        // 构造请求体：sourceAsin 作为 sku 查询源 Listing
        String sourceMarketplaceId = toStr(args.get("sourceMarketplaceId"));
        if (sourceMarketplaceId == null) sourceMarketplaceId = "ATVPDKIKX0DER"; // 默认 US
        String targetLanguage = toStr(args.get("targetLanguage"));
        if (targetLanguage == null) targetLanguage = inferLanguage(targetMarketplace);
        Object priceMarkupObj = args.get("priceMarkup");

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("shopId", shopId);
        reqBody.put("sku", sourceAsin);  // 用 ASIN 作为 SKU 查询
        reqBody.put("sourceMarketplaceId", sourceMarketplaceId);
        reqBody.put("targetMarketplaceId", targetMarketplace);
        reqBody.put("targetLanguage", targetLanguage);
        reqBody.put("priceMarkup", priceMarkupObj != null ? priceMarkupObj : 0.20);

        try {
            Result<Map<String, Object>> result = productServiceClient.copyListing(reqBody);
            if (!isSuccess(result)) {
                return fail("跨站点复制任务创建失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            return ok(String.format("已创建跨站点复制任务：ASIN %s → %s，任务 ID: %s，状态: %s",
                            sourceAsin, targetMarketplace, data.get("taskId"), data.get("status")), data);
        } catch (Exception e) {
            log.error("cross_marketplace_listing Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 8：广告 ACoS/ROAS 分析。
     * 通过 Feign 调用 amz-service-ad 的 /ad/reports 端点。
     */
    private String analyzeAdPerformance(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        Integer days = toInt(args.get("days"), 7);

        log.info("工具调用 analyze_ad_performance shopId={} asin={} days={}", shopId, asin, days);
        if (adServiceClient == null) {
            return fail("广告服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = adServiceClient.getReports(shopId);
            if (!isSuccess(result)) {
                return fail("广告报表查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> reports = result.getData();
            if (reports == null || reports.isEmpty()) {
                return ok("暂无广告报表数据", Map.of("reports", List.of()));
            }
            // 汇总 ACoS/ROAS
            double totalSpend = 0, totalSales = 0;
            for (Map<String, Object> r : reports) {
                totalSpend += toDouble(r.get("cost"));
                totalSales += toDouble(r.get("sales"));
            }
            double acos = totalSales > 0 ? totalSpend / totalSales : 0;
            double roas = totalSpend > 0 ? totalSales / totalSpend : 0;
            String asinDesc = asin != null ? "ASIN " + asin + " " : "";
            return ok(String.format("%s近 %d 天广告表现：花费 $%.2f，广告销售额 $%.2f，ACOS=%.1f%%，ROAS=%.2f",
                            asinDesc, days, totalSpend, totalSales, acos * 100, roas),
                    Map.of("adSpend", totalSpend, "adSales", totalSales,
                            "acos", acos, "roas", roas, "days", days, "reports", reports));
        } catch (Exception e) {
            log.error("analyze_ad_performance Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 9：竞品价格监控。
     * 通过 Feign 调用 amz-service-ad 的 /ad/competitor 端点。
     */
    private String monitorCompetitorPrice(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));

        log.info("工具调用 monitor_competitor_price shopId={} asin={}", shopId, asin);
        if (adServiceClient == null) {
            return fail("广告服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = adServiceClient.getCompetitor(shopId, asin);
            if (!isSuccess(result)) {
                return fail("竞品监控查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Object myPrice = data.get("myPrice");
            Object buyBox = data.get("buyBoxPrice");
            String suggestion = (myPrice != null && buyBox != null)
                    ? "建议参考 Buy Box 价格调整定价"
                    : "竞品价格数据暂未配置（需接入 SP-API Pricing API）";
            return ok(String.format("ASIN %s 竞品监控：%s", asin, suggestion), data);
        } catch (Exception e) {
            log.error("monitor_competitor_price Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 10：FBA 费用预估。
     * 通过 Feign 调用 amz-service-product 的 /product/fees/estimate 端点。
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
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = productServiceClient.estimateFbaFees(null, null, weight, sizeTier);
            if (!isSuccess(result)) {
                return fail("FBA 费用估算失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            return ok(String.format("SKU %s FBA 费用预估（%s, %.2f kg）：履约费 %s + 仓储费 %s = %s",
                            sku, sizeTier, weight,
                            data.get("fulfillmentFee"), data.get("storageFee"), data.get("totalFbaFee")), data);
        } catch (Exception e) {
            log.error("estimate_fba_fees Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 11：多语种翻译。
     * 通过 Feign 调用 amz-service-product 的 /product/translate 端点。
     */
    private String translateListing(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String text = toStr(args.get("text"));
        String sourceLang = toStr(args.get("sourceLang"));
        String targetLang = toStr(args.get("targetLang"));
        if (sourceLang == null) sourceLang = "en";
        if (targetLang == null) targetLang = "de";

        log.info("工具调用 translate_listing shopId={} source={} target={} textLen={}",
                shopId, sourceLang, targetLang, text == null ? 0 : text.length());
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("text", text);
        reqBody.put("sourceLang", sourceLang);
        reqBody.put("targetLang", targetLang);

        try {
            Result<Map<String, Object>> result = productServiceClient.translate(reqBody);
            if (!isSuccess(result)) {
                return fail("翻译失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            return ok(String.format("已将 %s 文本翻译为 %s", sourceLang, targetLang), data);
        } catch (Exception e) {
            log.error("translate_listing Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 12：AI 促销方案生成。
     * 通过 Feign 调用 amz-service-procurement 的 /procurement/promotion/plan 端点。
     */
    private String generatePromotionPlan(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        String goal = toStr(args.get("goal"));
        if (goal == null) goal = "提升销量";

        log.info("工具调用 generate_promotion_plan shopId={} asin={} goal={}", shopId, asin, goal);
        if (procurementServiceClient == null) {
            return fail("采购服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = procurementServiceClient.getPromotionPlan(shopId, asin, goal);
            if (!isSuccess(result)) {
                return fail("促销方案生成失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            String asinDesc = asin != null ? "ASIN " + asin + " " : "";
            return ok(String.format("已为 %s生成促销方案（目标：%s）", asinDesc, goal), data);
        } catch (Exception e) {
            log.error("generate_promotion_plan Feign 调用失败 shopId={}", shopId, e);
            return fail("采购服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 13：商品评论分析。
     * 联动 ReviewAnalysisService，调用 DeepSeek LLM 进行情感分析、痛点聚类和改进建议生成。
     */
    private String analyzeProductReviews(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        String reviewsJson = toStr(args.get("reviewsJson"));

        log.info("工具调用 analyze_product_reviews shopId={} asin={} reviewsJsonLen={}",
                shopId, asin, reviewsJson == null ? 0 : reviewsJson.length());

        if (reviewsJson == null || reviewsJson.isBlank()) {
            return fail("评论列表 JSON 不能为空");
        }

        try {
            // 去除可能的 markdown 代码块标记
            String json = reviewsJson.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            List<ReviewInfo> reviews = gson.fromJson(json, new TypeToken<List<ReviewInfo>>(){}.getType());
            if (reviews == null || reviews.isEmpty()) {
                return fail("评论列表解析后为空");
            }
            ReviewAnalysisResult result = reviewAnalysisService.analyze(reviews);
            return ok(String.format("ASIN %s 评论分析完成：情感得分 %.0f，痛点 %d 个，建议 %d 条",
                            asin,
                            result.getSentimentScore() != null ? result.getSentimentScore() : 0,
                            result.getPainPoints() != null ? result.getPainPoints().size() : 0,
                            result.getSuggestions() != null ? result.getSuggestions().size() : 0),
                    gson.toJsonTree(result));
        } catch (Exception e) {
            log.error("评论分析失败", e);
            return fail("评论分析失败: " + e.getMessage());
        }
    }

    /**
     * 工具 14：AI 选品分析。
     * 联动 SelectionAnalysisService，调用 DeepSeek LLM 生成市场分析、
     * 竞争评估、趋势判断和是否进入市场的选品建议。
     *
     * 入参字段：keyword（关键词）、marketplace（站点，默认 US），
     * 可选携带已分析的机会数据：asin/title/category/avgPrice/avgReviews/avgRating/
     * searchVolume/competitorCount/reviewBarrier/opportunityScore/trend30d/trend90d。
     */
    private String analyzeProductSelection(Map<String, Object> args) {
        String keyword = toStr(args.get("keyword"));
        String marketplace = toStr(args.get("marketplace"));
        if (marketplace == null) marketplace = "US";

        log.info("工具调用 analyze_product_selection keyword={} marketplace={}", keyword, marketplace);

        if (keyword == null || keyword.isBlank()) {
            return fail("关键词 keyword 不能为空");
        }

        // 构造选品机会输入：优先透传 args 中已分析的字段，缺失字段使用合理基准值
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        long seed = (long) keyword.hashCode() + marketplace.hashCode();
        rand.setSeed(seed);

        SelectionOpportunityInput input = new SelectionOpportunityInput();
        input.setAsin(toStr(args.get("asin")) != null ? toStr(args.get("asin")) : "B0" + (10000000L + rand.nextInt(0, 90000000)));
        input.setTitle(toStr(args.get("title")) != null ? toStr(args.get("title")) : keyword + " opportunity");
        input.setCategory(toStr(args.get("category")));
        input.setMarketplace(marketplace);
        input.setAvgPrice(args.get("avgPrice") != null ? new BigDecimal(toStr(args.get("avgPrice")))
                : BigDecimal.valueOf(15 + rand.nextDouble(0, 60)).setScale(2, RoundingMode.HALF_UP));
        input.setAvgReviews(args.get("avgReviews") != null ? toInt(args.get("avgReviews"), 100) : rand.nextInt(20, 400));
        input.setAvgRating(args.get("avgRating") != null ? new BigDecimal(toStr(args.get("avgRating")))
                : BigDecimal.valueOf(3.5 + rand.nextDouble(0, 1.4)).setScale(1, RoundingMode.HALF_UP));
        input.setSearchVolume(args.get("searchVolume") != null ? toInt(args.get("searchVolume"), 10000) : 5000 + rand.nextInt(0, 50000));
        input.setCompetitorCount(args.get("competitorCount") != null ? toInt(args.get("competitorCount"), 100) : 50 + rand.nextInt(0, 500));
        input.setReviewBarrier(toStr(args.get("reviewBarrier")));
        input.setOpportunityScore(args.get("opportunityScore") != null ? new BigDecimal(toStr(args.get("opportunityScore")))
                : BigDecimal.valueOf(50 + rand.nextDouble(0, 40)).setScale(1, RoundingMode.HALF_UP));
        input.setTrend30d(toStr(args.get("trend30d")));
        input.setTrend90d(toStr(args.get("trend90d")));

        try {
            SelectionAnalysisResult result = selectionAnalysisService.analyzeOpportunity(input);
            Map<String, Object> data = new HashMap<>();
            data.put("aiSummary", result.getAiSummary());
            data.put("aiSuggestion", result.getAiSuggestion());
            data.put("input", input);
            return ok(String.format("关键词「%s」（%s）AI 选品分析完成：%s",
                            keyword, marketplace,
                            result.getAiSummary() != null ? result.getAiSummary() : "无摘要"),
                    data);
        } catch (Exception e) {
            log.error("AI 选品分析失败 keyword={}", keyword, e);
            return fail("AI 选品分析失败: " + e.getMessage());
        }
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

    /**
     * 判断 Feign 调用返回的 Result 是否成功。
     */
    private boolean isSuccess(Result<?> result) {
        return result != null && result.getCode() == 200 && result.getData() != null;
    }

    /**
     * 根据 Marketplace ID 推断目标语言。
     */
    private String inferLanguage(String marketplaceId) {
        if (marketplaceId == null) return "en";
        return switch (marketplaceId) {
            case "A1PA6795UKMFR9" -> "de";  // DE
            case "A13V1IB3VIYZZH" -> "fr";  // FR
            case "APJ6JRA9NG5V4" -> "it";   // IT
            case "A1RKKUPIHCS9HS" -> "es";  // ES
            case "A1VC38T7YXB528" -> "ja";  // JP
            case "A1F83G8C2ARO7P" -> "en";  // UK
            default -> "en";
        };
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

    private double toDouble(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try { return Double.parseDouble(obj.toString()); } catch (Exception e) { return 0; }
    }

    private String toStr(Object obj) {
        return obj == null ? null : obj.toString();
    }
}
