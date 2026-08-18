package com.amz.agent;

import com.amz.agent.review.ReviewAnalysisResult;
import com.amz.agent.review.ReviewAnalysisService;
import com.amz.agent.selection.SelectionAnalysisResult;
import com.amz.agent.selection.SelectionAnalysisService;
import com.amz.client.AdServiceClient;
import com.amz.client.InventoryServiceClient;
import com.amz.client.LogisticsServiceClient;
import com.amz.client.OrderServiceClient;
import com.amz.client.ProcurementServiceClient;
import com.amz.client.ProductServiceClient;
import com.amz.client.ReportServiceClient;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ERP Agent 工具调度器单元测试（工具路由）。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>无效调用（null / name 为 null）→ fail</li>
 *   <li>未知工具名 → fail "未知工具"</li>
 *   <li>Feign 客户端未启用（null）→ fail "客户端未启用"</li>
 *   <li>query_orders：成功 / 失败结果 / 异常降级</li>
 *   <li>query_inventory：成功 + sku 过滤</li>
 *   <li>suggest_replenish：空建议列表 → ok "暂无补货建议"</li>
 *   <li>analyze_product_reviews：空 JSON → fail</li>
 *   <li>analyze_product_selection：空关键词 → fail；合法 → 调用服务成功</li>
 *   <li>analyze_listing_health / analyze_search_terms / analyze_sales_trend：真实后端接线（Item1）</li>
 *   <li>analyze_inventory_aging / optimize_ad_campaign / optimize_listing_seo：真实/推导接线（Item1）</li>
 * </ul>
 * <p>
 * 说明：所有 Feign 客户端与 AI 服务均 mock，不依赖 Spring 容器与外部网络。
 */
@DisplayName("ErpToolExecutor 工具调度器测试")
class ErpToolExecutorTest {

    private OrderServiceClient orderServiceClient;
    private InventoryServiceClient inventoryServiceClient;
    private AdServiceClient adServiceClient;
    private ProductServiceClient productServiceClient;
    private ProcurementServiceClient procurementServiceClient;
    private ReportServiceClient reportServiceClient;
    private LogisticsServiceClient logisticsServiceClient;
    private ReviewAnalysisService reviewAnalysisService;
    private SelectionAnalysisService selectionAnalysisService;
    private ErpToolExecutor executor;

    @BeforeEach
    void setUp() {
        orderServiceClient = mock(OrderServiceClient.class);
        inventoryServiceClient = mock(InventoryServiceClient.class);
        adServiceClient = mock(AdServiceClient.class);
        productServiceClient = mock(ProductServiceClient.class);
        procurementServiceClient = mock(ProcurementServiceClient.class);
        reportServiceClient = mock(ReportServiceClient.class);
        logisticsServiceClient = mock(LogisticsServiceClient.class);
        reviewAnalysisService = mock(ReviewAnalysisService.class);
        selectionAnalysisService = mock(SelectionAnalysisService.class);

        executor = new ErpToolExecutor();
        ReflectionTestUtils.setField(executor, "orderServiceClient", orderServiceClient);
        ReflectionTestUtils.setField(executor, "inventoryServiceClient", inventoryServiceClient);
        ReflectionTestUtils.setField(executor, "adServiceClient", adServiceClient);
        ReflectionTestUtils.setField(executor, "productServiceClient", productServiceClient);
        ReflectionTestUtils.setField(executor, "procurementServiceClient", procurementServiceClient);
        ReflectionTestUtils.setField(executor, "reportServiceClient", reportServiceClient);
        ReflectionTestUtils.setField(executor, "logisticsServiceClient", logisticsServiceClient);
        ReflectionTestUtils.setField(executor, "reviewAnalysisService", reviewAnalysisService);
        ReflectionTestUtils.setField(executor, "selectionAnalysisService", selectionAnalysisService);
    }

    private JsonObject json(String result) {
        return JsonParser.parseString(result).getAsJsonObject();
    }

    // ===== 工具路由 =====

    @Test
    @DisplayName("execute(null) → 应返回 ok=false")
    void testNullCallFails() {
        JsonObject json = json(executor.execute(null));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("无效"));
    }

    @Test
    @DisplayName("execute(name=null) → 应返回 ok=false")
    void testNullNameFails() {
        FunctionCall call = new FunctionCall();
        call.setName(null);
        JsonObject json = json(executor.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
    }

    @Test
    @DisplayName("execute(未知工具名) → 应返回 ok=false，message 含「未知工具」")
    void testUnknownToolFails() {
        FunctionCall call = new FunctionCall();
        call.setName("nonexistent_tool");
        JsonObject json = json(executor.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("未知工具"));
    }

    @Test
    @DisplayName("execute(工具名含空格) → trim 后应正确路由到 query_orders")
    void testToolNameTrimmed() {
        FunctionCall call = new FunctionCall();
        call.setName("  query_orders  ");
        Map<String, Object> data = new HashMap<>();
        data.put("count", 3);
        data.put("totalAmount", "$50");
        when(orderServiceClient.getOrderList(nullable(Long.class), any()))
                .thenReturn(Result.success(data));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean(), "trim 后应正确路由");
        assertTrue(json.get("message").getAsString().contains("3 个订单"));
    }

    @Test
    @DisplayName("工具调用 arguments 为 null 时应按空 Map 处理，不抛 NPE")
    void testNullArgumentsHandled() {
        FunctionCall call = new FunctionCall();
        call.setName("query_orders");
        call.setArguments(null);
        Map<String, Object> data = new HashMap<>();
        data.put("count", 0);
        when(orderServiceClient.getOrderList(nullable(Long.class), any()))
                .thenReturn(Result.success(data));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
    }

    // ===== query_orders =====

    @Test
    @DisplayName("query_orders：客户端未启用（null）→ 应返回 ok=false")
    void testQueryOrdersClientNullFails() {
        ErpToolExecutor noClientExecutor = new ErpToolExecutor();
        ReflectionTestUtils.setField(noClientExecutor, "reviewAnalysisService", reviewAnalysisService);
        ReflectionTestUtils.setField(noClientExecutor, "selectionAnalysisService", selectionAnalysisService);

        FunctionCall call = new FunctionCall();
        call.setName("query_orders");
        JsonObject json = json(noClientExecutor.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("订单服务客户端未启用"));
    }

    @Test
    @DisplayName("query_orders：成功应返回 ok=true，message 含订单数")
    void testQueryOrdersSuccess() {
        FunctionCall call = new FunctionCall();
        call.setName("query_orders");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1);
        args.put("days", 7);
        call.setArguments(args);

        Map<String, Object> data = new HashMap<>();
        data.put("count", 42);
        data.put("totalAmount", "$1234.56");
        when(orderServiceClient.getOrderList(1L, 7)).thenReturn(Result.success(data));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("42 个订单"));
        assertNotNull(json.get("data"));
    }

    @Test
    @DisplayName("query_orders：Result 失败（code!=200）→ 应返回 ok=false")
    void testQueryOrdersResultFailure() {
        FunctionCall call = new FunctionCall();
        call.setName("query_orders");

        when(orderServiceClient.getOrderList(nullable(Long.class), any()))
                .thenReturn(Result.failure("服务降级"));

        JsonObject json = json(executor.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("订单查询失败"));
    }

    @Test
    @DisplayName("query_orders：Feign 抛异常 → 应降级返回 ok=false「订单服务暂时不可用」")
    void testQueryOrdersExceptionFallback() {
        FunctionCall call = new FunctionCall();
        call.setName("query_orders");

        when(orderServiceClient.getOrderList(nullable(Long.class), any()))
                .thenThrow(new RuntimeException("connection refused"));

        JsonObject json = json(executor.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("订单服务暂时不可用"));
    }

    // ===== query_inventory =====

    @Test
    @DisplayName("query_inventory：成功 + sku 过滤应只统计匹配 SKU 的可售库存")
    void testQueryInventoryWithSkuFilter() {
        FunctionCall call = new FunctionCall();
        call.setName("query_inventory");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1);
        args.put("sku", "SKU-A");
        call.setArguments(args);

        Map<String, Object> item1 = new HashMap<>();
        item1.put("sku", "SKU-A");
        item1.put("availableQuantity", 10);
        Map<String, Object> item2 = new HashMap<>();
        item2.put("sku", "SKU-B");
        item2.put("availableQuantity", 99);
        List<Map<String, Object>> list = Arrays.asList(item1, item2);

        when(inventoryServiceClient.getInventory(anyLong())).thenReturn(Result.success(list));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        // 只统计 SKU-A 的 10 件
        assertTrue(json.get("message").getAsString().contains("10 件"));
    }

    // ===== suggest_replenish =====

    @Test
    @DisplayName("suggest_replenish：空建议列表 → ok=true「暂无补货建议」")
    void testSuggestReplenishEmpty() {
        FunctionCall call = new FunctionCall();
        call.setName("suggest_replenish");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1);
        args.put("sku", "SKU-X");
        call.setArguments(args);

        when(inventoryServiceClient.getReplenishSuggest(anyLong(), any()))
                .thenReturn(Result.success(List.of()));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("暂无补货建议"));
    }

    @Test
    @DisplayName("suggest_replenish：有建议 → ok=true，message 含建议补货量")
    void testSuggestReplenishWithData() {
        FunctionCall call = new FunctionCall();
        call.setName("suggest_replenish");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1);
        args.put("sku", "SKU-Y");
        call.setArguments(args);

        Map<String, Object> sug = new HashMap<>();
        sug.put("sku", "SKU-Y");
        sug.put("currentTotalStock", 5);
        sug.put("suggestedReplenishQty", 50);
        when(inventoryServiceClient.getReplenishSuggest(anyLong(), any()))
                .thenReturn(Result.success(List.of(sug)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("50"));
    }

    // ===== analyze_product_reviews =====

    @Test
    @DisplayName("analyze_product_reviews：reviewsJson 为空 → ok=false")
    void testAnalyzeReviewsEmptyJsonFails() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_product_reviews");
        Map<String, Object> args = new HashMap<>();
        args.put("asin", "B0TEST");
        args.put("reviewsJson", "");
        call.setArguments(args);

        JsonObject json = json(executor.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("不能为空"));
    }

    @Test
    @DisplayName("analyze_product_reviews：合法 JSON → 调用 ReviewAnalysisService 返回 ok=true")
    void testAnalyzeReviewsValid() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_product_reviews");
        Map<String, Object> args = new HashMap<>();
        args.put("asin", "B0TEST");
        args.put("reviewsJson", "[{\"rating\":5,\"content\":\"good\"}]");
        call.setArguments(args);

        ReviewAnalysisResult result = new ReviewAnalysisResult();
        result.setSentimentScore(85.0);
        result.setPainPoints(List.of("包装破损"));
        result.setSuggestions(List.of("改进包装"));
        when(reviewAnalysisService.analyze(any())).thenReturn(result);

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("85"));
    }

    // ===== analyze_product_selection =====

    @Test
    @DisplayName("analyze_product_selection：关键词为空 → ok=false")
    void testAnalyzeSelectionBlankKeywordFails() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_product_selection");
        Map<String, Object> args = new HashMap<>();
        args.put("keyword", "");
        call.setArguments(args);

        JsonObject json = json(executor.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("keyword 不能为空"));
    }

    @Test
    @DisplayName("analyze_product_selection：合法关键词已正确路由（刻画当前 setSeed 限制，未走 default 分支）")
    void testAnalyzeSelectionValidKeywordRoutesToMethod() {
        // 说明：生产代码 ErpToolExecutor.analyzeProductSelection 调用 ThreadLocalRandom.setSeed，
        // 该方法在 JDK 中会抛 UnsupportedOperationException（已知生产限制）。
        // 按任务约束「不改业务代码」，此处仅刻画当前实际行为：合法关键词不会走到「未知工具」default 分支，
        // 而是确实路由进了 analyzeProductSelection 方法（随后因 setSeed 限制抛异常）。
        // 修复该生产限制后，应将本用例改为验证 SelectionAnalysisService 被调用且返回 ok=true。
        FunctionCall call = new FunctionCall();
        call.setName("analyze_product_selection");
        Map<String, Object> args = new HashMap<>();
        args.put("keyword", "无线耳机");
        args.put("marketplace", "US");
        call.setArguments(args);

        SelectionAnalysisResult result = new SelectionAnalysisResult();
        result.setAiSummary("市场潜力中等");
        result.setAiSuggestion("建议小批量试水");
        when(selectionAnalysisService.analyzeOpportunity(any())).thenReturn(result);

        // 合法关键词路由进 analyzeProductSelection，现走 selectionAnalysisService（已替换 ThreadLocalRandom.setSeed 限制）
        // 验证服务被调用且返回 ok=true
        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertNotNull(json.get("data"));
    }

    // ===== 工具 19-24：真实数据接入（Item1）=====

    @Test
    @DisplayName("analyzeListingHealth：客户端未启用 → ok=false")
    void testListingHealthClientNullFails() {
        ErpToolExecutor noClient = new ErpToolExecutor();
        ReflectionTestUtils.setField(noClient, "reviewAnalysisService", reviewAnalysisService);
        ReflectionTestUtils.setField(noClient, "selectionAnalysisService", selectionAnalysisService);
        FunctionCall call = new FunctionCall();
        call.setName("analyze_listing_health");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("asin", "B0X");
        call.setArguments(args);
        JsonObject json = json(noClient.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("商品服务客户端未启用"));
    }

    @Test
    @DisplayName("analyzeListingHealth：命中 ASIN → ok=true，返回真实健康度与问题项")
    void testListingHealthMatch() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_listing_health");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("asin", "B0X");
        call.setArguments(args);

        Map<String, Object> h = new HashMap<>();
        h.put("asin", "B0X");
        h.put("healthScore", 70);
        h.put("severity", "AT_RISK");
        h.put("titleOk", true);
        h.put("searchTermsOk", false);
        h.put("imagesOk", false);
        h.put("aplusOk", true);
        h.put("bulletPointsOk", true);
        when(productServiceClient.getListingHealthList(anyLong())).thenReturn(Result.success(List.of(h)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean(), "应返回真实数据");
        String msg = json.get("message").getAsString();
        assertTrue(msg.contains("真实数据"), msg);
        assertTrue(msg.contains("70"), msg);
    }

    @Test
    @DisplayName("analyzeListingHealth：未命中 ASIN → ok=true，提示未查询到记录（非失败）")
    void testListingHealthNotFound() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_listing_health");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("asin", "B0NOT");
        call.setArguments(args);

        Map<String, Object> h = new HashMap<>();
        h.put("asin", "B0OTHER");
        when(productServiceClient.getListingHealthList(anyLong())).thenReturn(Result.success(List.of(h)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("未查询到 ASIN"));
    }

    @Test
    @DisplayName("analyzeSearchTerms：成功 → ok=true，返回真实出单/浪费词与 ACoS")
    void testSearchTermsSuccess() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_search_terms");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        call.setArguments(args);

        Map<String, Object> data = new HashMap<>();
        data.put("convertingTerms", 12);
        data.put("wasteTerms", 5);
        data.put("overallAcos", new java.math.BigDecimal("28.5"));
        when(adServiceClient.analyzeSearchTerms(any(), any(), any())).thenReturn(Result.success(data));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("真实数据"));
    }

    @Test
    @DisplayName("analyzeSalesTrend：成功返回日聚合 → ok=true，含趋势方向与峰值")
    void testSalesTrendSuccess() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_sales_trend");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("days", 3);
        call.setArguments(args);

        Map<String, Object> d1 = new HashMap<>(); d1.put("day", "2026-08-15"); d1.put("value", 100.0);
        Map<String, Object> d2 = new HashMap<>(); d2.put("day", "2026-08-16"); d2.put("value", 200.0);
        Map<String, Object> d3 = new HashMap<>(); d3.put("day", "2026-08-17"); d3.put("value", 150.0);
        when(reportServiceClient.getSalesTrend(any(), any())).thenReturn(Result.success(Arrays.asList(d1, d2, d3)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("真实数据"));
        assertTrue(json.get("message").getAsString().contains("2026-08-16"));
    }

    @Test
    @DisplayName("analyzeSalesTrend：空序列 → ok=true，提示暂无数据")
    void testSalesTrendEmpty() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_sales_trend");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        call.setArguments(args);
        when(reportServiceClient.getSalesTrend(any(), any())).thenReturn(Result.success(List.of()));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("暂无销售趋势数据"));
    }

    @Test
    @DisplayName("analyzeInventoryAging：真实库存 → ok=true，按可供天数估算库龄并标记滞销")
    void testInventoryAging() {
        FunctionCall call = new FunctionCall();
        call.setName("analyze_inventory_aging");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        call.setArguments(args);

        Map<String, Object> skuFast = new HashMap<>();
        skuFast.put("availableQuantity", 10);
        skuFast.put("avg30Days", 5.0);
        skuFast.put("healthStatus", "HEALTHY");
        Map<String, Object> skuSlow = new HashMap<>();
        skuSlow.put("availableQuantity", 1000);
        skuSlow.put("avg30Days", 1.0);
        skuSlow.put("healthStatus", "OVERSTOCK");
        when(inventoryServiceClient.getInventory(anyLong())).thenReturn(Result.success(Arrays.asList(skuFast, skuSlow)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        String msg = json.get("message").getAsString();
        assertTrue(msg.contains("真实库存数据"), msg);
        assertTrue(msg.contains("滞销"), msg);
        assertEquals(1, json.getAsJsonObject("data").get("slowMoverCount").getAsInt());
    }

    @Test
    @DisplayName("optimizeAdCampaign：真实报表 → ok=true，按 cost/sales 计算当前 ACoS")
    void testOptimizeAdCampaign() {
        FunctionCall call = new FunctionCall();
        call.setName("optimize_ad_campaign");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        call.setArguments(args);

        Map<String, Object> r1 = new HashMap<>();
        r1.put("campaignId", "C1");
        r1.put("cost", 30.0);
        r1.put("sales", 100.0);
        when(adServiceClient.getReports(anyLong())).thenReturn(Result.success(List.of(r1)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("真实报表数据"));
    }

    @Test
    @DisplayName("optimizeListingSeo：命中 ASIN → ok=true，由真实健康度推导 SEO 建议")
    void testOptimizeListingSeo() {
        FunctionCall call = new FunctionCall();
        call.setName("optimize_listing_seo");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("asin", "B0X");
        call.setArguments(args);

        Map<String, Object> h = new HashMap<>();
        h.put("asin", "B0X");
        h.put("healthScore", 60);
        h.put("titleOk", true);
        h.put("searchTermsOk", false);
        h.put("bulletPointsOk", true);
        h.put("imagesOk", true);
        h.put("aplusOk", false);
        when(productServiceClient.getListingHealthList(anyLong())).thenReturn(Result.success(List.of(h)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("基于真实健康度推导"));
    }

    // ===== 工具 25-27：物流/采购真实数据接入（Item1 收尾）=====

    @Test
    @DisplayName("optimizeShippingRoute：客户端未启用 → ok=false")
    void testShippingRouteClientNullFails() {
        ErpToolExecutor noClient = new ErpToolExecutor();
        ReflectionTestUtils.setField(noClient, "reviewAnalysisService", reviewAnalysisService);
        ReflectionTestUtils.setField(noClient, "selectionAnalysisService", selectionAnalysisService);
        FunctionCall call = new FunctionCall();
        call.setName("optimize_shipping_route");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("orderId", "ORD-001");
        call.setArguments(args);
        JsonObject json = json(noClient.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("物流服务客户端未启用"));
    }

    @Test
    @DisplayName("optimizeShippingRoute：真实报价 → ok=true，message 含「真实报价」")
    void testShippingRouteSuccess() {
        FunctionCall call = new FunctionCall();
        call.setName("optimize_shipping_route");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("orderId", "ORD-001");
        call.setArguments(args);

        Map<String, Object> opt1 = new HashMap<>();
        opt1.put("warehouse", "FBA-ON1");
        opt1.put("cost", 3.22);
        opt1.put("transitDays", "2-day Prime");
        Map<String, Object> opt2 = new HashMap<>();
        opt2.put("warehouse", "LAX-OVS");
        opt2.put("cost", 5.80);
        opt2.put("transitDays", "3-5 days");
        Map<String, Object> data = new HashMap<>();
        data.put("options", List.of(opt2, opt1)); // 非排序，需内部按 cost 排序
        when(logisticsServiceClient.compareShippingQuotes(anyLong(), any(), any(), nullable(Double.class), nullable(Double.class)))
                .thenReturn(Result.success(data));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        String msg = json.get("message").getAsString();
        assertTrue(msg.contains("真实报价"), msg);
        assertTrue(msg.contains("FBA-ON1"), msg); // 应选 cost 最低的
        JsonObject dataObj = json.getAsJsonObject("data");
        assertTrue(dataObj.has("recommended"));
    }

    @Test
    @DisplayName("optimizeInventoryDistribution：客户端未启用 → ok=false")
    void testInventoryDistClientNullFails() {
        ErpToolExecutor noClient = new ErpToolExecutor();
        ReflectionTestUtils.setField(noClient, "reviewAnalysisService", reviewAnalysisService);
        ReflectionTestUtils.setField(noClient, "selectionAnalysisService", selectionAnalysisService);
        FunctionCall call = new FunctionCall();
        call.setName("optimize_inventory_distribution");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        call.setArguments(args);
        JsonObject json = json(noClient.execute(call));
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("物流服务客户端未启用"));
    }

    @Test
    @DisplayName("optimizeInventoryDistribution：真实多仓视图 → ok=true，message 含「真实多仓」")
    void testInventoryDistributionSuccess() {
        FunctionCall call = new FunctionCall();
        call.setName("optimize_inventory_distribution");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        call.setArguments(args);

        Map<String, Object> data = new HashMap<>();
        data.put("totalSkuCount", 150);
        data.put("lowStockSkuCount", 12);
        data.put("warehouses", List.of("FBA-ON1", "FBA-LAX"));
        when(logisticsServiceClient.getGlobalInventoryView(anyLong()))
                .thenReturn(Result.success(data));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        String msg = json.get("message").getAsString();
        assertTrue(msg.contains("真实多仓库存数据"), msg);
        assertTrue(msg.contains("150"), msg);
    }

    @Test
    @DisplayName("createPurchasePlan：供应商比价成功 → ok=true，含供应商名（无 DEMO 前缀）")
    void testPurchasePlanWithCompare() {
        FunctionCall call = new FunctionCall();
        call.setName("create_purchase_plan");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("sku", "SKU-A");
        args.put("quantity", 200);
        call.setArguments(args);

        Map<String, Object> supplier = new HashMap<>();
        supplier.put("supplierName", "优质供应商A");
        supplier.put("unitPrice", 10.5);
        supplier.put("rating", "A");
        when(procurementServiceClient.compareSupplierPrices(anyLong(), any()))
                .thenReturn(Result.success(List.of(supplier)));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        String msg = json.get("message").getAsString();
        assertFalse(msg.startsWith("[演示估算"), "不应含演示估算前缀");
        assertTrue(msg.contains("优质供应商A"), msg);
    }

    @Test
    @DisplayName("createPurchasePlan：比价失败回退 → ok=true，含「演示估算」标注")
    void testPurchasePlanFallbackToDemo() {
        FunctionCall call = new FunctionCall();
        call.setName("create_purchase_plan");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", 1L);
        args.put("sku", "SKU-B");
        args.put("quantity", 100);
        call.setArguments(args);

        when(procurementServiceClient.compareSupplierPrices(anyLong(), any()))
                .thenThrow(new RuntimeException("service unavailable"));

        JsonObject json = json(executor.execute(call));
        assertTrue(json.get("ok").getAsBoolean());
        String msg = json.get("message").getAsString();
        assertTrue(msg.startsWith("[演示估算"), "比价失败时应回退演示估算并标注");
    }
}
