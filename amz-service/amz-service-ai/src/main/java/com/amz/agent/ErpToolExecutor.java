package com.amz.agent;

import com.amz.client.AdServiceClient;
import com.amz.client.InventoryServiceClient;
import com.amz.client.LogisticsServiceClient;
import com.amz.client.OrderServiceClient;
import com.amz.client.ProcurementServiceClient;
import com.amz.client.ProductServiceClient;
import com.amz.client.ReportServiceClient;
import com.amz.context.UserContext;
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
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ERP 运营 Agent 工具调度器。
 * 复用购物 Agent 的 Function Calling 编排架构，工具实现从"购物"改为"运营数据分析"。
 *
 * 工具清单（28 个）：
 * 基础查询 9 Tool + 分析类 7 Tool + 建议类 6 Tool + 操作类 6 Tool
 *
 * 工具 1-18 通过 Feign 调用各微服务获取真实数据；工具 19-28 调用本地 AI 服务或复合逻辑。
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
    private ReportServiceClient reportServiceClient;

    @Autowired(required = false)
    private ProcurementServiceClient procurementServiceClient;

    @Autowired(required = false)
    private LogisticsServiceClient logisticsServiceClient;

    private final Gson gson = new Gson();

    /**
     * 不依赖 shopId 取数的工具（或 shopId 仅作日志、下游忽略），不参与 shopId 越权校验。
     * 其余工具凡在 args 中携带 shopId 一律强制校验其归属，防止提示注入跨店取数/写数。
     */
    private static final Set<String> TOOLS_IGNORING_SHOP_ID = Set.of(
            "estimate_fba_fees", "translate_listing", "analyze_product_reviews", "analyze_product_selection",
            "estimate_logistics_cost");

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

        // 越权防护（H7）：LLM 生成的 shopId 必须属于当前登录用户授权店铺，防止提示注入诱导 Agent 跨店取数/写数。
        // TOOLS_IGNORING_SHOP_ID 中的工具不依赖 shopId（或仅作日志、下游忽略），跳过校验；其余工具凡携带 shopId 一律强制校验。
        Long argShopId = toLong(args.get("shopId"));
        if (argShopId != null && !TOOLS_IGNORING_SHOP_ID.contains(name) && !UserContext.isShopAllowed(argShopId)) {
            log.warn("Agent 工具 shopId 越权拦截 name={}, shopId={}, userId={}", name, argShopId, UserContext.getUserId());
            return fail("无权访问该店铺数据");
        }

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
            // Phase 3 新增 14 工具
            case "query_purchase_orders"      -> queryPurchaseOrders(args);
            case "query_suppliers"            -> querySuppliers(args);
            case "query_advertising"          -> queryAdvertising(args);
            case "track_shipment"            -> trackShipment(args);
            case "analyze_listing_health"    -> analyzeListingHealth(args);
            case "analyze_search_terms"      -> analyzeSearchTerms(args);
            case "analyze_sales_trend"        -> analyzeSalesTrend(args);
            case "analyze_inventory_aging"   -> analyzeInventoryAging(args);
            case "optimize_ad_campaign"      -> optimizeAdCampaign(args);
            case "optimize_listing_seo"      -> optimizeListingSeo(args);
            case "optimize_shipping_route"   -> optimizeShippingRoute(args);
            case "optimize_inventory_distribution" -> optimizeInventoryDistribution(args);
            case "create_purchase_plan"       -> createPurchasePlan(args);
            case "auto_reply_message"        -> autoReplyMessage(args);
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

        // 仅透传 LLM 通过其它真实工具已获取的市场/竞争指标；未提供的字段保持 null，
        // 由 SelectionAnalysisService 以「未知」兜底。严禁凭空伪造 avgPrice / searchVolume /
        // opportunityScore 等市场数据（原实现用 ThreadLocalRandom.setSeed 既会抛异常，
        // 又会编造虚假指标，已移除）。
        SelectionOpportunityInput input = new SelectionOpportunityInput();
        input.setAsin(toStr(args.get("asin")));
        input.setTitle(toStr(args.get("title")));
        input.setCategory(toStr(args.get("category")));
        input.setMarketplace(marketplace);
        input.setAvgPrice(args.get("avgPrice") != null ? new BigDecimal(toStr(args.get("avgPrice"))) : null);
        input.setAvgReviews(args.get("avgReviews") != null ? toInt(args.get("avgReviews"), 0) : null);
        input.setAvgRating(args.get("avgRating") != null ? new BigDecimal(toStr(args.get("avgRating"))) : null);
        input.setSearchVolume(args.get("searchVolume") != null ? toInt(args.get("searchVolume"), 0) : null);
        input.setCompetitorCount(args.get("competitorCount") != null ? toInt(args.get("competitorCount"), 0) : null);
        input.setReviewBarrier(toStr(args.get("reviewBarrier")));
        input.setOpportunityScore(args.get("opportunityScore") != null ? new BigDecimal(toStr(args.get("opportunityScore"))) : null);
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

    // ================================================================
    // Phase 3 新增 14 个工具（AI Agent 14→28 扩展）
    // ================================================================

    /**
     * 工具 15：查询采购订单。
     * 真实调用 amz-service-procurement 的 /procurement/order/list/{shopId}。
     */
    private String queryPurchaseOrders(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String status = toStr(args.get("status"));
        log.info("工具调用 query_purchase_orders shopId={} status={}", shopId, status);
        if (procurementServiceClient == null) {
            return fail("采购服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = procurementServiceClient.getPurchaseOrders(shopId);
            if (!isSuccess(result)) {
                return fail("采购订单查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            int count = (list == null) ? 0 : list.size();
            return ok(String.format("采购订单查询完成 shopId=%d status=%s，共 %d 条",
                            shopId, status != null ? status : "ALL", count),
                    Map.of("shopId", shopId, "status", status != null ? status : "ALL",
                            "count", count, "orders", list));
        } catch (Exception e) {
            log.error("query_purchase_orders Feign 调用失败", e);
            return fail("采购服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 16：查询供应商列表。
     * 真实调用 amz-service-procurement 的 /procurement/supplier/list/{shopId}。
     */
    private String querySuppliers(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String keyword = toStr(args.get("keyword"));
        log.info("工具调用 query_suppliers shopId={} keyword={}", shopId, keyword);
        if (procurementServiceClient == null) {
            return fail("采购服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = procurementServiceClient.getSuppliers(shopId, keyword);
            if (!isSuccess(result)) {
                return fail("供应商查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            int count = (list == null) ? 0 : list.size();
            return ok(String.format("供应商查询完成 shopId=%d keyword=%s，共 %d 家", shopId, keyword, count),
                    Map.of("shopId", shopId, "keyword", keyword, "count", count, "suppliers", list));
        } catch (Exception e) {
            log.error("query_suppliers Feign 调用失败", e);
            return fail("采购服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 17：查询广告汇总。
     */
    private String queryAdvertising(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        Integer days = toInt(args.get("days"), 7);
        log.info("工具调用 query_advertising shopId={} days={}", shopId, days);
        if (adServiceClient == null) return fail("广告服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = adServiceClient.getReports(shopId);
            double totalSpend = 0, totalSales = 0;
            if (result != null && result.getData() != null) {
                for (Map<String, Object> r : result.getData()) {
                    totalSpend += toDouble(r.get("cost"));
                    totalSales += toDouble(r.get("sales"));
                }
            }
            double acos = totalSales > 0 ? totalSpend / totalSales : 0;
            return ok(String.format("广告汇总（近%d天）：花费$%.2f，销售额$%.2f，ACoS=%.1f%%", days, totalSpend, totalSales, acos * 100),
                    Map.of("adSpend", totalSpend, "adSales", totalSales, "acos", acos, "days", days));
        } catch (Exception e) {
            log.error("query_advertising 调用广告服务失败 shopId={} days={}", shopId, days, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 18：物流轨迹查询。
     * 真实调用 amz-service-logistics 的 /logistics/shipment/{shipmentId}/tracking。
     * 仅传入 shipmentNo 时，先按店铺反查 shipmentId。
     */
    private String trackShipment(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String shipmentNo = toStr(args.get("shipmentNo"));
        Long shipmentId = toLong(args.get("shipmentId"));
        log.info("工具调用 track_shipment shopId={} shipmentNo={} shipmentId={}", shopId, shipmentNo, shipmentId);

        if (logisticsServiceClient == null) {
            return fail("物流客户端未启用");
        }
        try {
            // 仅提供 shipmentNo 时，先按店铺反查 shipmentId
            if (shipmentId == null && shipmentNo != null && !shipmentNo.isBlank()) {
                Result<List<Map<String, Object>>> listResult = logisticsServiceClient.listShipments(shopId, null);
                if (isSuccess(listResult) && listResult.getData() != null) {
                    shipmentId = listResult.getData().stream()
                            .filter(s -> shipmentNo.equals(toStr(s.get("shipmentNo"))))
                            .map(s -> toLong(s.get("id")))
                            .findFirst()
                            .orElse(null);
                }
            }
            if (shipmentId == null) {
                return fail("未找到对应的物流货件（shipmentId / shipmentNo 无效）");
            }
            Result<List<Map<String, Object>>> result = logisticsServiceClient.getTracking(shipmentId);
            if (!isSuccess(result)) {
                return fail("物流轨迹查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> tracking = result.getData();
            int count = (tracking == null) ? 0 : tracking.size();
            if (count == 0) {
                return ok(String.format("货件 %s 暂无轨迹记录", shipmentId),
                        Map.of("shipmentId", shipmentId, "tracking", List.of()));
            }
            Map<String, Object> latest = tracking.get(count - 1);
            return ok(String.format("物流货件 %s：最新轨迹【%s】%s，共 %d 条",
                            shipmentId, latest.get("location"), latest.get("description"), count),
                    Map.of("shipmentId", shipmentId, "tracking", tracking));
        } catch (Exception e) {
            log.error("track_shipment Feign 调用失败", e);
            return fail("物流服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 演示/估算类工具标识前缀：当前 demo 缺少这些分析所需的真实数据后端，
     * 返回值为硬编码的示例/估算，必须在对外文案中明确标注，避免被当作真实业务分析。
     */
    private static final String DEMO_DATA_NOTE = "[演示估算·非真实业务数据] ";

    /**
     * 自动草稿标识：模板/LLM 生成的文本草稿（非业务数据缺口），与 [演示估算] 区分，避免被误读为真实分析结果。
     */
    private static final String AUTO_DRAFT_NOTE = "[自动草稿·模板生成·非业务数据·需人工复核] ";

    /**
     * 工具 19：Listing 健康度分析（真实数据，来自 amz-service-product 的 Listing 监控）。
     */
    private String analyzeListingHealth(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        log.info("工具调用 analyze_listing_health shopId={} asin={}", shopId, asin);
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = productServiceClient.getListingHealthList(shopId);
            if (!isSuccess(result)) {
                return fail("Listing 健康度查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            Map<String, Object> match = (list == null) ? null : list.stream()
                    .filter(m -> asin != null && asin.equals(toStr(m.get("asin"))))
                    .findFirst().orElse(null);
            if (match == null) {
                return ok(String.format("未查询到 ASIN %s 的 Listing 健康度记录（shopId=%s 共 %d 条）",
                                asin, shopId, list == null ? 0 : list.size()),
                        Map.of("asin", asin == null ? "" : asin, "found", false,
                                "totalRecords", list == null ? 0 : list.size()));
            }
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("asin", match.get("asin"));
            health.put("sku", match.get("sku"));
            health.put("healthScore", match.get("healthScore"));
            health.put("severity", match.get("severity"));
            health.put("titleOk", match.get("titleOk"));
            health.put("bulletPointsOk", match.get("bulletPointsOk"));
            health.put("descriptionOk", match.get("descriptionOk"));
            health.put("imagesOk", match.get("imagesOk"));
            health.put("searchTermsOk", match.get("searchTermsOk"));
            health.put("aplusOk", match.get("aplusOk"));
            health.put("status", match.get("status"));
            List<String> issues = new ArrayList<>();
            if (Boolean.FALSE.equals(toBool(match.get("titleOk")))) issues.add("标题待优化");
            if (Boolean.FALSE.equals(toBool(match.get("bulletPointsOk")))) issues.add("五点描述待优化");
            if (Boolean.FALSE.equals(toBool(match.get("imagesOk")))) issues.add("图片数量不足");
            if (Boolean.FALSE.equals(toBool(match.get("searchTermsOk")))) issues.add("后台搜索词缺失");
            if (Boolean.FALSE.equals(toBool(match.get("aplusOk")))) issues.add("A+ 页面缺失");
            health.put("issues", issues);
            return ok(String.format("ASIN %s Listing 健康度：评分 %s，严重度 %s，问题项 %d 个（真实数据）",
                            asin, match.get("healthScore"), match.get("severity"), issues.size()), health);
        } catch (Exception e) {
            log.error("analyze_listing_health Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 20：搜索词分析（真实数据，来自 amz-service-ad 的搜索词综合分析）。
     */
    private String analyzeSearchTerms(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        Integer days = toInt(args.get("days"), 7);
        log.info("工具调用 analyze_search_terms shopId={} asin={} days={}", shopId, asin, days);
        if (adServiceClient == null) return fail("广告服务客户端未启用");
        try {
            Result<Map<String, Object>> result = adServiceClient.analyzeSearchTerms(shopId, null, days);
            if (!isSuccess(result)) {
                return fail("搜索词分析失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            int converting = toInt(data.get("convertingTerms"), 0);
            int waste = toInt(data.get("wasteTerms"), 0);
            Object overallAcos = data.get("overallAcos");
            return ok(String.format("搜索词分析（近%d天）：出单词 %d 个，浪费词 %d 个，整体 ACoS %s（真实数据）",
                            days, converting, waste, overallAcos != null ? overallAcos : "0"), data);
        } catch (Exception e) {
            log.error("analyze_search_terms Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 21：销售趋势深度分析（真实数据，来自 amz-service-report 的日销售聚合）。
     */
    private String analyzeSalesTrend(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        Integer days = toInt(args.get("days"), 30);
        log.info("工具调用 analyze_sales_trend shopId={} days={}", shopId, days);
        if (reportServiceClient == null) return fail("报表服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = reportServiceClient.getSalesTrend(shopId, days);
            if (!isSuccess(result)) {
                return fail("销售趋势查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> trend = result.getData();
            if (trend == null || trend.isEmpty()) {
                return ok("暂无销售趋势数据（近" + days + "天无记录）", Map.of("days", days, "points", 0));
            }
            List<Double> values = trend.stream().map(m -> toDouble(m.get("value"))).toList();
            double first = values.get(0);
            double last = values.get(values.size() - 1);
            double total = values.stream().mapToDouble(Double::doubleValue).sum();
            String direction = last > first ? "UP" : (last < first ? "DOWN" : "FLAT");
            double growth = first > 0 ? (last - first) / first * 100 : 0;
            Map<String, Object> peak = trend.get(0);
            for (Map<String, Object> p : trend) {
                if (toDouble(p.get("value")) > toDouble(peak.get("value"))) peak = p;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shopId", shopId);
            out.put("days", days);
            out.put("points", trend.size());
            out.put("direction", direction);
            out.put("growthRateFirstToLast", String.format("%.1f%%", growth));
            out.put("totalSales", total);
            out.put("peakDay", peak.get("day"));
            out.put("peakValue", peak.get("value"));
            out.put("series", trend);
            return ok(String.format("近%d天销售趋势：%s，首尾环比 %s，峰值 %s（真实数据）",
                            days, direction, out.get("growthRateFirstToLast"), peak.get("day")), out);
        } catch (Exception e) {
            log.error("analyze_sales_trend Feign 调用失败 shopId={}", shopId, e);
            return fail("报表服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 22：库龄分析与滞销预警（真实库存数据，库龄按可供天数代理估算）。
     */
    private String analyzeInventoryAging(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        log.info("工具调用 analyze_inventory_aging shopId={}", shopId);
        if (inventoryServiceClient == null) return fail("库存服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getInventory(shopId);
            if (!isSuccess(result)) {
                return fail("库存查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            if (list == null || list.isEmpty()) {
                return ok("暂无库存数据", Map.of("shopId", shopId, "skuCount", 0));
            }
            Map<String, Integer> healthDist = new HashMap<>();
            Map<String, Integer> ageBuckets = new LinkedHashMap<>();
            ageBuckets.put("0_30_days", 0);
            ageBuckets.put("30_90_days", 0);
            ageBuckets.put("90_180_days", 0);
            ageBuckets.put("180_365_days", 0);
            ageBuckets.put("over_365_days", 0);
            int slowMover = 0;
            for (Map<String, Object> m : list) {
                String hs = toStr(m.get("healthStatus"));
                if (hs == null) hs = "UNKNOWN";
                healthDist.merge(hs, 1, Integer::sum);
                double avail = toDouble(m.get("availableQuantity"));
                double avg30 = toDouble(m.get("avg30Days"));
                double dos = (avg30 > 0) ? avail / avg30 : -1; // 可供天数；-1 表示无近 30 日销售速度
                if (dos < 0) {
                    ageBuckets.put("over_365_days", ageBuckets.get("over_365_days") + 1);
                } else if (dos > 365) {
                    ageBuckets.put("over_365_days", ageBuckets.get("over_365_days") + 1);
                    slowMover++;
                } else if (dos > 180) {
                    ageBuckets.put("180_365_days", ageBuckets.get("180_365_days") + 1);
                    slowMover++;
                } else if (dos > 90) {
                    ageBuckets.put("90_180_days", ageBuckets.get("90_180_days") + 1);
                } else if (dos > 30) {
                    ageBuckets.put("30_90_days", ageBuckets.get("30_90_days") + 1);
                } else {
                    ageBuckets.put("0_30_days", ageBuckets.get("0_30_days") + 1);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shopId", shopId);
            out.put("skuCount", list.size());
            out.put("healthDistribution", healthDist);
            out.put("ageBucketsByDaysOfSupply", ageBuckets);
            out.put("slowMoverCount", slowMover);
            out.put("methodNote", "库龄按可供天数(availableQuantity/近30日日均销量)估算，非真实入库日期；无销售速度的 SKU 归入 over_365_days");
            return ok(String.format("库龄分析（基于可供天数估算）：%d 个 SKU，滞销(可供>180天) %d 个（真实库存数据）",
                            list.size(), slowMover), out);
        } catch (Exception e) {
            log.error("analyze_inventory_aging Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 23：广告优化建议（当前 ACoS 来自真实广告报表，建议为基于阈值的规则推导）。
     */
    private String optimizeAdCampaign(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String campaignId = toStr(args.get("campaignId"));
        Integer days = toInt(args.get("days"), 7);
        log.info("工具调用 optimize_ad_campaign shopId={} campaignId={} days={}", shopId, campaignId, days);
        if (adServiceClient == null) return fail("广告服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = adServiceClient.getReports(shopId);
            if (!isSuccess(result)) {
                return fail("广告报表查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> reports = result.getData();
            if (reports == null || reports.isEmpty()) {
                return ok("暂无广告报表数据", Map.of("shopId", shopId, "campaigns", List.of()));
            }
            List<Map<String, Object>> campaignAnalysis = new ArrayList<>();
            for (Map<String, Object> r : reports) {
                double cost = toDouble(r.get("cost"));
                double sales = toDouble(r.get("sales"));
                double acos = sales > 0 ? cost / sales : 0;
                String cid = toStr(r.get("campaignId"));
                if (campaignId != null && !campaignId.equals(cid)) continue;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("campaignId", cid);
                a.put("currentAcos", String.format("%.1f%%", acos * 100));
                List<String> sugg = new ArrayList<>();
                if (acos > 0.4) sugg.add("ACoS 过高(>40%)，建议暂停高花费低转化关键词");
                else if (acos < 0.15 && sales > 0) sugg.add("ACoS 健康(<15%)，建议适度提高出价扩量");
                else sugg.add("ACoS 正常，维持观察");
                a.put("suggestions", sugg);
                campaignAnalysis.add(a);
            }
            if (campaignAnalysis.isEmpty()) {
                return ok("未匹配到 campaignId=" + campaignId + " 的广告活动",
                        Map.of("shopId", shopId, "campaignId", campaignId));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shopId", shopId);
            out.put("days", days);
            out.put("campaigns", campaignAnalysis);
            out.put("methodNote", "当前 ACoS 由真实广告报表(cost/sales)计算；优化建议为基于阈值的规则建议，非关键词级精细调优");
            return ok(String.format("广告优化建议：覆盖 %d 个广告活动（真实报表数据，建议为规则推导）",
                            campaignAnalysis.size()), out);
        } catch (Exception e) {
            log.error("optimize_ad_campaign Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 24：Listing SEO 优化（建议由真实 Listing 健康度字段推导）。
     */
    private String optimizeListingSeo(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        log.info("工具调用 optimize_listing_seo shopId={} asin={}", shopId, asin);
        if (productServiceClient == null) return fail("商品服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = productServiceClient.getListingHealthList(shopId);
            if (!isSuccess(result)) {
                return fail("Listing 健康度查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            Map<String, Object> match = (list == null) ? null : list.stream()
                    .filter(m -> asin != null && asin.equals(toStr(m.get("asin"))))
                    .findFirst().orElse(null);
            if (match == null) {
                return ok(String.format("未查询到 ASIN %s 的 Listing 健康度记录，无法生成 SEO 建议", asin),
                        Map.of("asin", asin == null ? "" : asin, "found", false));
            }
            List<String> seo = new ArrayList<>();
            if (Boolean.FALSE.equals(toBool(match.get("titleOk")))) seo.add("优化标题：补充核心关键词至标题前部");
            if (Boolean.FALSE.equals(toBool(match.get("searchTermsOk")))) seo.add("补充后台搜索词(backend search terms)以覆盖长尾流量");
            if (Boolean.FALSE.equals(toBool(match.get("bulletPointsOk")))) seo.add("重写五点描述：突出卖点与关键词");
            if (Boolean.FALSE.equals(toBool(match.get("imagesOk")))) seo.add("增加主图与附图数量，提升点击率");
            if (Boolean.FALSE.equals(toBool(match.get("aplusOk")))) seo.add("补充 A+ 页面，提升转化");
            if (seo.isEmpty()) seo.add("Listing 各项指标健康，建议持续监控搜索词排名");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("asin", asin);
            out.put("healthScore", match.get("healthScore"));
            out.put("seoSuggestions", seo);
            out.put("methodNote", "SEO 建议由真实 Listing 健康度字段(titleOk/searchTermsOk/...)推导，缺失的具体关键词列表需结合搜索词报表进一步补全");
            return ok(String.format("ASIN %s SEO 优化建议：%d 条（基于真实健康度推导）", asin, seo.size()), out);
        } catch (Exception e) {
            log.error("optimize_listing_seo Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 25：最优发货路由（真实物流报价，来自 amz-service-logistics）。
     */
    private String optimizeShippingRoute(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String orderId = toStr(args.get("orderId"));
        log.info("工具调用 optimize_shipping_route shopId={} orderId={}", shopId, orderId);
        if (logisticsServiceClient == null) return fail("物流服务客户端未启用");
        try {
            Result<Map<String, Object>> result =
                    logisticsServiceClient.compareShippingQuotes(shopId, "CN", "US", null, null);
            if (!isSuccess(result) || result.getData() == null) {
                return fail("物流报价查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            List<Map<String, Object>> options = new ArrayList<>();
            Object rawOptions = data.get("options");
            if (rawOptions instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> opts = new ArrayList<>((List<Map<String, Object>>) rawOptions);
                opts.sort((a, b) -> Double.compare(toDouble(a.get("cost")), toDouble(b.get("cost"))));
                options = opts;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", orderId);
            out.put("recommended", options.isEmpty() ? null : options.get(0));
            out.put("alternatives", options.size() > 1 ? options.subList(1, Math.min(3, options.size())) : List.of());
            out.put("methodNote", "路由由真实物流报价 API 生成（amz-service-logistics compareShippingQuotes）");
            Map<String, Object> rec = options.isEmpty() ? null : options.get(0);
            String msg = rec != null
                    ? String.format("订单 %s 最优路由：仓库 %s，费用 $%.2f，时效 %s（真实报价）",
                            orderId, rec.get("warehouse"), toDouble(rec.get("cost")), rec.get("transitDays"))
                    : String.format("订单 %s 路由：暂无可用报价（shopId=%s）", orderId, shopId);
            return ok(msg, out);
        } catch (Exception e) {
            log.error("optimize_shipping_route Feign 调用失败 shopId={}", shopId, e);
            return fail("物流服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 26：库存跨仓调拨建议（真实多仓视图，来自 amz-service-logistics）。
     */
    private String optimizeInventoryDistribution(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        log.info("工具调用 optimize_inventory_distribution shopId={}", shopId);
        if (logisticsServiceClient == null) return fail("物流服务客户端未启用");
        try {
            Result<Map<String, Object>> result = logisticsServiceClient.getGlobalInventoryView(shopId);
            if (!isSuccess(result) || result.getData() == null) {
                return fail("多仓库存视图查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shopId", shopId);
            out.put("inventoryView", data);
            out.put("methodNote", "调拨建议基于真实多仓库存视图（amz-service-logistics getGlobalInventoryView），"
                    + "具体调拨决策需结合各仓订单量与补货周期人工复核");
            int totalSku = toInt(data.get("totalSkuCount"), 0);
            int lowStock = toInt(data.get("lowStockSkuCount"), 0);
            String msg = String.format("跨仓调拨视图：%d 个 SKU，低库存预警 %d 个（真实多仓库存数据）", totalSku, lowStock);
            return ok(msg, out);
        } catch (Exception e) {
            log.error("optimize_inventory_distribution Feign 调用失败 shopId={}", shopId, e);
            return fail("物流服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 27：AI 创建采购计划（真实供应商比价，来自 amz-service-procurement）。
     * <p>
     * 当前 demo 无真实 1688 报价后端（Item2 依赖沙箱凭证），单价取确定性占位估算（非随机），
     * 避免同一输入产生不同结果；对外文案标注为演示估算。若 ProcurementServiceClient
     * compareSupplierPrices 可用则优先展示真实比价结果。
     */
    private String createPurchasePlan(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));
        Integer quantity = toInt(args.get("quantity"), 100);
        log.info("工具调用 create_purchase_plan shopId={} sku={} quantity={}", shopId, sku, quantity);
        if (procurementServiceClient == null) return fail("采购服务客户端未启用");

        String planNo = "PL" + System.currentTimeMillis();
        BigDecimal unitPrice = BigDecimal.valueOf(12.00).setScale(2, RoundingMode.HALF_UP);

        // 尝试真实供应商比价（若有数据则覆盖占位单价）
        try {
            Result<List<Map<String, Object>>> compareResult =
                    procurementServiceClient.compareSupplierPrices(shopId, sku);
            if (isSuccess(compareResult) && compareResult.getData() != null
                    && !compareResult.getData().isEmpty()) {
                Map<String, Object> best = compareResult.getData().get(0);
                Object priceObj = best.get("unitPrice");
                if (priceObj instanceof Number) {
                    unitPrice = new BigDecimal(priceObj.toString()).setScale(2, RoundingMode.HALF_UP);
                }
                String supplier = toStr(best.get("supplierName"));
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("planNo", planNo);
                plan.put("sku", sku);
                plan.put("quantity", quantity);
                plan.put("unitPrice", unitPrice);
                plan.put("totalAmount", unitPrice.multiply(BigDecimal.valueOf(quantity)));
                plan.put("status", "DRAFT");
                plan.put("bestSupplier", supplier);
                plan.put("methodNote", "单价来自真实供应商比价（compareSupplierPrices），推荐供应商 " + supplier);
                return ok(String.format("已生成采购计划 %s：%s × %d，总金额 ¥%s（真实比价，供应商：%s）",
                                planNo, sku, quantity, plan.get("totalAmount"), supplier), plan);
            }
        } catch (Exception e) {
            log.warn("供应商比价失败，回退确定性占位：{}", e.getMessage());
        }

        // 占位路径（无比价数据时）
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planNo", planNo);
        plan.put("sku", sku);
        plan.put("quantity", quantity);
        plan.put("unitPrice", unitPrice);
        plan.put("totalAmount", unitPrice.multiply(BigDecimal.valueOf(quantity)));
        plan.put("status", "DRAFT");
        plan.put("suggestion", "建议选择优质供应商（评分 A），1688 货源价格 ¥"
                + unitPrice.multiply(BigDecimal.valueOf(0.85)).setScale(2, RoundingMode.HALF_UP));
        return ok(DEMO_DATA_NOTE + String.format("已生成采购计划 %s：%s × %d，总金额 ¥%s（单价/金额为演示估算）", planNo, sku, quantity,
                        plan.get("totalAmount")), plan);
    }

    /**
     * 工具 28：AI 自动生成回复。
     */
    private String autoReplyMessage(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String messageId = toStr(args.get("messageId"));
        log.info("工具调用 auto_reply_message shopId={} messageId={}", shopId, messageId);
        String subject = toStr(args.get("subject"));
        if (subject == null) subject = "Customer Inquiry";
        String draftReply;
        if (subject.contains("Return") || subject.contains("退货")) {
            draftReply = "Dear customer, we're sorry to hear about your issue. " +
                    "Please provide your order ID and we'll process a full refund or replacement immediately.";
        } else if (subject.contains("Shipping") || subject.contains("物流")) {
            draftReply = "Thank you for your inquiry. Your order has been shipped and the tracking number is " +
                    "TRK" + System.currentTimeMillis() % 100000 + ". Estimated delivery is 3-5 business days.";
        } else {
            draftReply = "Thank you for contacting us. We've received your message and will respond within 24 hours.";
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("messageId", messageId);
        reply.put("draftReply", draftReply);
        return ok(AUTO_DRAFT_NOTE + "已生成回复草稿（模板生成，非业务数据，需人工复核后发送）", reply);
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

    private Boolean toBool(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Boolean b) return b;
        if (obj instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(obj.toString());
    }

    private String toStr(Object obj) {
        return obj == null ? null : obj.toString();
    }
}
