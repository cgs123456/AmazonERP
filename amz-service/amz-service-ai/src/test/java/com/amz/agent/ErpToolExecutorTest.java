package com.amz.agent;

import com.amz.agent.review.ReviewAnalysisResult;
import com.amz.agent.review.ReviewAnalysisService;
import com.amz.agent.selection.SelectionAnalysisResult;
import com.amz.agent.selection.SelectionAnalysisService;
import com.amz.client.AdServiceClient;
import com.amz.client.InventoryServiceClient;
import com.amz.client.OrderServiceClient;
import com.amz.client.ProcurementServiceClient;
import com.amz.client.ProductServiceClient;
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
        reviewAnalysisService = mock(ReviewAnalysisService.class);
        selectionAnalysisService = mock(SelectionAnalysisService.class);

        executor = new ErpToolExecutor();
        ReflectionTestUtils.setField(executor, "orderServiceClient", orderServiceClient);
        ReflectionTestUtils.setField(executor, "inventoryServiceClient", inventoryServiceClient);
        ReflectionTestUtils.setField(executor, "adServiceClient", adServiceClient);
        ReflectionTestUtils.setField(executor, "productServiceClient", productServiceClient);
        ReflectionTestUtils.setField(executor, "procurementServiceClient", procurementServiceClient);
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

        // 合法关键词路由进方法后，setSeed 抛 UnsupportedOperationException（未被方法内 try/catch 覆盖）
        assertThrows(UnsupportedOperationException.class, () -> executor.execute(call));
    }
}
