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

    @Tool("分析商品评论，返回情感得分、痛点聚类、改进建议和综合总结。reviewsJson 为评论数组的 JSON 字符串，每条包含 rating/title/content/date/verifiedPurchase 字段")
    String analyzeProductReviews(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin,
            @P("评论列表 JSON 字符串，格式：[{\"rating\":1,\"title\":\"...\",\"content\":\"...\",\"date\":\"2026-07-01\",\"verifiedPurchase\":true}]") String reviewsJson) {
        log.info("LangChain4j 工具调用: analyze_product_reviews shopId={} asin={} reviewsJsonLen={}",
                shopId, asin, reviewsJson == null ? 0 : reviewsJson.length());
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", shopId);
        args.put("asin", asin);
        args.put("reviewsJson", reviewsJson);
        return toolExecutor.execute(buildCall("analyze_product_reviews", args));
    }

    @Tool("AI 选品分析，基于关键词与站点生成市场容量、竞争程度、趋势与是否进入市场的建议")
    String analyzeProductSelection(
            @P("待分析关键词，如 wireless earbuds") String keyword,
            @P("站点代码，如 US/UK/DE/JP，默认 US") String marketplace) {
        log.info("LangChain4j 工具调用: analyze_product_selection keyword={} marketplace={}",
                keyword, marketplace);
        return toolExecutor.execute(buildCall("analyze_product_selection",
                Map.of("keyword", keyword, "marketplace", marketplace != null ? marketplace : "US")));
    }

    // ===== Phase 3 新增 14 个工具（Phase 1+2 模块联动） =====

    @Tool("查询采购订单，可按状态筛选")
    String queryPurchaseOrders(
            @P("店铺 ID") Long shopId,
            @P("订单状态：DRAFT/SUBMITTED/APPROVED/IN_PROCESS/COMPLETED，默认 ALL") String status) {
        log.info("LangChain4j 工具调用: query_purchase_orders shopId={} status={}", shopId, status);
        return toolExecutor.execute(buildCall("query_purchase_orders",
                Map.of("shopId", shopId, "status", status != null ? status : "ALL")));
    }

    @Tool("查询供应商列表，支持关键词模糊搜索")
    String querySuppliers(
            @P("店铺 ID") Long shopId,
            @P("搜索关键词") String keyword) {
        log.info("LangChain4j 工具调用: query_suppliers shopId={} keyword={}", shopId, keyword);
        return toolExecutor.execute(buildCall("query_suppliers",
                Map.of("shopId", shopId, "keyword", keyword)));
    }

    @Tool("查询广告汇总数据，含花费、销售额和 ACoS")
    String queryAdvertising(
            @P("店铺 ID") Long shopId,
            @P("查询天数，默认 7") Integer days) {
        log.info("LangChain4j 工具调用: query_advertising shopId={} days={}", shopId, days);
        return toolExecutor.execute(buildCall("query_advertising",
                Map.of("shopId", shopId, "days", days != null ? days : 7)));
    }

    @Tool("物流轨迹查询，返回货运单号的最新物流状态和路由节点")
    String trackShipment(
            @P("店铺 ID") Long shopId,
            @P("物流单号") String shipmentNo) {
        log.info("LangChain4j 工具调用: track_shipment shopId={} shipmentNo={}", shopId, shipmentNo);
        return toolExecutor.execute(buildCall("track_shipment",
                Map.of("shopId", shopId, "shipmentNo", shipmentNo)));
    }

    @Tool("Listing 健康度分析，返回标题/五点/图片/搜索词各项得分和优化建议")
    String analyzeListingHealth(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin) {
        log.info("LangChain4j 工具调用: analyze_listing_health shopId={} asin={}", shopId, asin);
        return toolExecutor.execute(buildCall("analyze_listing_health",
                Map.of("shopId", shopId, "asin", asin)));
    }

    @Tool("搜索词分析，识别出单词、浪费词并给出竞价优化建议")
    String analyzeSearchTerms(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin) {
        log.info("LangChain4j 工具调用: analyze_search_terms shopId={} asin={}", shopId, asin);
        return toolExecutor.execute(buildCall("analyze_search_terms",
                Map.of("shopId", shopId, "asin", asin)));
    }

    @Tool("销售趋势深度分析，含环比增长、峰值时段、季节性预判")
    String analyzeSalesTrend(
            @P("店铺 ID") Long shopId,
            @P("分析天数，默认 30") Integer days) {
        log.info("LangChain4j 工具调用: analyze_sales_trend shopId={} days={}", shopId, days);
        return toolExecutor.execute(buildCall("analyze_sales_trend",
                Map.of("shopId", shopId, "days", days != null ? days : 30)));
    }

    @Tool("库龄分析与滞销预警，返回按库龄分组的库存数量和超 365 天滞销品处理建议")
    String analyzeInventoryAging(
            @P("店铺 ID") Long shopId) {
        log.info("LangChain4j 工具调用: analyze_inventory_aging shopId={}", shopId);
        return toolExecutor.execute(buildCall("analyze_inventory_aging",
                Map.of("shopId", shopId)));
    }

    @Tool("广告优化建议，返回关键词调整/预算优化/否定关键词等动作")
    String optimizeAdCampaign(
            @P("店铺 ID") Long shopId,
            @P("广告活动 ID") String campaignId) {
        log.info("LangChain4j 工具调用: optimize_ad_campaign shopId={} campaignId={}", shopId, campaignId);
        return toolExecutor.execute(buildCall("optimize_ad_campaign",
                Map.of("shopId", shopId, "campaignId", campaignId)));
    }

    @Tool("Listing SEO 优化，检查缺失的关键词、标题优化点和后台搜索词建议")
    String optimizeListingSeo(
            @P("店铺 ID") Long shopId,
            @P("Amazon ASIN") String asin) {
        log.info("LangChain4j 工具调用: optimize_listing_seo shopId={} asin={}", shopId, asin);
        return toolExecutor.execute(buildCall("optimize_listing_seo",
                Map.of("shopId", shopId, "asin", asin)));
    }

    @Tool("根据订单目的地和库存分布推荐最优发货仓库和物流商")
    String optimizeShippingRoute(
            @P("店铺 ID") Long shopId,
            @P("订单 ID") String orderId) {
        log.info("LangChain4j 工具调用: optimize_shipping_route shopId={} orderId={}", shopId, orderId);
        return toolExecutor.execute(buildCall("optimize_shipping_route",
                Map.of("shopId", shopId, "orderId", orderId)));
    }

    @Tool("库存跨仓调拨建议，根据各仓库存水平和区域订单量给出调拨方案")
    String optimizeInventoryDistribution(
            @P("店铺 ID") Long shopId) {
        log.info("LangChain4j 工具调用: optimize_inventory_distribution shopId={}", shopId);
        return toolExecutor.execute(buildCall("optimize_inventory_distribution",
                Map.of("shopId", shopId)));
    }

    @Tool("AI 创建采购计划，根据 SKU 和数量自动估算金额并生成计划编号")
    String createPurchasePlan(
            @P("店铺 ID") Long shopId,
            @P("卖家 SKU") String sku,
            @P("采购数量") Integer quantity) {
        log.info("LangChain4j 工具调用: create_purchase_plan shopId={} sku={} quantity={}", shopId, sku, quantity);
        return toolExecutor.execute(buildCall("create_purchase_plan",
                Map.of("shopId", shopId, "sku", sku, "quantity", quantity != null ? quantity : 100)));
    }

    @Tool("AI 自动生成买家消息回复，智能识别退货/物流/通用场景并生成英文回信")
    String autoReplyMessage(
            @P("店铺 ID") Long shopId,
            @P("消息 ID") String messageId,
            @P("消息主题（用于判断场景类型）") String subject) {
        log.info("LangChain4j 工具调用: auto_reply_message shopId={} messageId={}", shopId, messageId);
        return toolExecutor.execute(buildCall("auto_reply_message",
                Map.of("shopId", shopId, "messageId", messageId, "subject", subject != null ? subject : "Customer Inquiry")));
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
