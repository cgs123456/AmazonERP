package com.amz.controller;

import com.amz.agent.ErpAdTools;
import com.amz.agent.ErpAnalysisTools;
import com.amz.agent.ErpInventoryTools;
import com.amz.agent.ErpLogisticsTools;
import com.amz.agent.ErpOrderTools;
import com.amz.agent.ErpProcurementTools;
import com.amz.agent.ErpProductTools;
import com.amz.agent.ErpToolExecutor;
import com.amz.client.AdServiceClient;
import com.amz.context.UserContext;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 广告托管（{@code POST /ai/ad-pilot/run}）契约测试。
 *
 * <p><b>这条测试为什么必须存在</b>：§S33.21 之前，该端点没有注入任何服务，
 * 返回的 {@code analysis} / {@code execution} 是两段写死的说明文字——
 * <b>无论传什么 shopId / campaignId，响应完全相同</b>。而它没有任何测试，
 * 所以「文档说有调用、代码里没有」这件事一直没被发现。
 *
 * <p>因此这里的断言刻意盯**行为差异**而不是文案：
 * <ul>
 *   <li>广告报表不同 → {@code analysis} 里出现不同的数字；</li>
 *   <li>报表为空 → {@code analysis} 明确说「暂无」，而不是给一个看起来像分析结果的字符串；</li>
 *   <li>工具链失败 → 返回码是 400 而不是 200（否则调用方会把空方案当成「没有可优化项」）。</li>
 * </ul>
 * 只断言「字段存在」是抓不到原缺陷的——原实现的字段一直都在。
 */
@DisplayName("AI 广告托管：必须真的调用工具链")
class AdPilotControllerTest {

    private AdPilotController controller;
    private AdServiceClient adServiceClient;

    @BeforeEach
    void setUp() {
        // 工具链按生产结构装配：executor 持有 7 个工具类，工具类持有各自客户端。
        // 不用 Spring 容器（依赖过重），但**结构与容器里一致**——这正是原缺陷
        // 在单测里看不出来的原因，所以这里显式把链路拼起来。
        ErpToolExecutor executor = new ErpToolExecutor();
        ErpAdTools adTools = new ErpAdTools();
        adServiceClient = mock(AdServiceClient.class);
        ReflectionTestUtils.setField(adTools, "adServiceClient", adServiceClient);
        ReflectionTestUtils.setField(executor, "adTools", adTools);
        ReflectionTestUtils.setField(executor, "analysisTools", new ErpAnalysisTools());
        ReflectionTestUtils.setField(executor, "orderTools", new ErpOrderTools());
        ReflectionTestUtils.setField(executor, "inventoryTools", new ErpInventoryTools());
        ReflectionTestUtils.setField(executor, "productTools", new ErpProductTools());
        ReflectionTestUtils.setField(executor, "procurementTools", new ErpProcurementTools());
        ReflectionTestUtils.setField(executor, "logisticsTools", new ErpLogisticsTools());

        controller = new AdPilotController();
        ReflectionTestUtils.setField(controller, "toolExecutor", executor);

        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShopId(1L);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static List<Map<String, Object>> reports(String campaignId, double cost, double sales) {
        Map<String, Object> row = new HashMap<>();
        row.put("campaignId", campaignId);
        row.put("cost", cost);
        row.put("sales", sales);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }

    @Test
    @DisplayName("分析结果来自真实报表：不同报表产出不同数字")
    void analysisComesFromRealReports() {
        when(adServiceClient.getReports(1L))
                .thenReturn(Result.success(reports("C-1", 30.0, 100.0)));

        Result<Map<String, Object>> first = controller.run(1L, null, "DRY_RUN", null);
        assertEquals(200, first.getCode());
        String firstAnalysis = String.valueOf(first.getData().get("analysis"));
        assertTrue(firstAnalysis.contains("ACOS=30.0%"),
                "分析应基于 cost/sales 算出的真实 ACoS，实际：" + firstAnalysis);

        // 换一份报表：同一个端点必须给出不同的数字。
        // 原实现（写死文案）在这一步会返回完全相同的字符串。
        when(adServiceClient.getReports(1L))
                .thenReturn(Result.success(reports("C-2", 80.0, 100.0)));
        Result<Map<String, Object>> second = controller.run(1L, null, "DRY_RUN", null);
        String secondAnalysis = String.valueOf(second.getData().get("analysis"));
        assertTrue(secondAnalysis.contains("ACOS=80.0%"),
                "换了报表后分析必须变化，实际：" + secondAnalysis);
    }

    @Test
    @DisplayName("报表为空时明确说「暂无」，不伪装成有分析结论")
    void emptyReportsSaySoExplicitly() {
        when(adServiceClient.getReports(1L)).thenReturn(Result.success(List.of()));

        Result<Map<String, Object>> res = controller.run(1L, null, "DRY_RUN", null);

        assertEquals(200, res.getCode());
        String analysis = String.valueOf(res.getData().get("analysis"));
        assertTrue(analysis.contains("暂无广告报表数据"), "实际：" + analysis);
        // 关键：不能出现看起来像结论的数字
        assertFalse(analysis.contains("ACOS="), "空报表不应产出 ACoS 结论，实际：" + analysis);
    }

    @Test
    @DisplayName("工具链失败时返回 400 且带上失败原因，不是 200 + 空方案")
    void toolFailureIsNotSilentlySuccessful() {
        when(adServiceClient.getReports(1L)).thenReturn(Result.failure("广告服务不可用"));

        Result<Map<String, Object>> res = controller.run(1L, null, "DRY_RUN", null);

        assertEquals(400, res.getCode(), "分析失败不能返回 200——否则调用方会当成「没有可优化项」");
        assertNotNull(res.getData(), "失败也要带上 plan，运营需要看到哪一步失败");
        assertTrue(res.getMessage().contains("广告分析失败"), "实际：" + res.getMessage());
        String analysis = String.valueOf(res.getData().get("analysis"));
        assertTrue(analysis.contains("广告报表查询失败"), "实际：" + analysis);
    }

    @Test
    @DisplayName("EXECUTE 模式必须要求 confirm=EXECUTE，否则不执行也不分析")
    void executeRequiresExplicitConfirmation() {
        Result<Map<String, Object>> res = controller.run(1L, null, "EXECUTE", null);

        assertEquals(400, res.getCode());
        assertNull(res.getData(), "参数校验失败不应产出方案");
        assertTrue(res.getMessage().contains("二次确认"), "实际：" + res.getMessage());
        // 没确认就一次报表都不该拉
        org.mockito.Mockito.verifyNoInteractions(adServiceClient);
    }

    @Test
    @DisplayName("EXECUTE 即使确认也不下发，并如实说明原因")
    void executeModeStillDoesNotDispatch() {
        when(adServiceClient.getReports(1L))
                .thenReturn(Result.success(reports("C-1", 20.0, 100.0)));

        Result<Map<String, Object>> res = controller.run(1L, null, "EXECUTE", "EXECUTE");

        assertEquals(200, res.getCode());
        assertEquals("EXECUTE", res.getData().get("mode"));
        String execution = String.valueOf(res.getData().get("execution"));
        assertTrue(execution.contains("未下发"), "实际：" + execution);
        assertFalse(execution.contains("已执行"), "不能声称已下发，实际：" + execution);
    }

    @Test
    @DisplayName("campaignId 下推给建议工具：只分析该活动")
    void campaignIdIsForwardedToSuggestionTool() {
        when(adServiceClient.getReports(1L)).thenReturn(Result.success(
                List.of(
                        row("C-1", 50.0, 100.0),
                        row("C-2", 10.0, 100.0))));

        Result<Map<String, Object>> res = controller.run(1L, "C-2", "DRY_RUN", null);

        String suggestions = String.valueOf(res.getData().get("suggestions"));
        assertTrue(suggestions.contains("覆盖 1 个广告活动"), "实际：" + suggestions);
    }

    private static Map<String, Object> row(String campaignId, double cost, double sales) {
        Map<String, Object> row = new HashMap<>();
        row.put("campaignId", campaignId);
        row.put("cost", cost);
        row.put("sales", sales);
        return row;
    }
}
