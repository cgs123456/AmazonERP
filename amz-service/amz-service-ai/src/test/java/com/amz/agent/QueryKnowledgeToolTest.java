package com.amz.agent;

import com.amz.ai.knowledge.KnowledgeChunk;
import com.amz.ai.knowledge.KnowledgeSearchService;
import com.amz.context.UserContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("query_knowledge_base 工具测试")
class QueryKnowledgeToolTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private static FunctionCall call(Object shopId, Object query, Object topN) {
        FunctionCall call = new FunctionCall();
        call.setName("query_knowledge_base");
        Map<String, Object> args = new HashMap<>();
        args.put("shopId", shopId);
        args.put("query", query);
        args.put("topN", topN);
        call.setArguments(args);
        return call;
    }

    @Test
    @DisplayName("命中时返回 ok 与结构化 chunks")
    void testHit() throws Exception {
        UserContext.setUserId(1);
        UserContext.setShops(List.of(1L));
        KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .docId("d").filename("退货政策.pdf").chunkIndex(0)
                .content("退货政策说明").score(2.0).build();
        when(searchService.search(1L, "退货政策", 5)).thenReturn(List.of(chunk));
        ErpToolExecutor executor = new ErpToolExecutor();
        // §S32：query_knowledge_base 在 ErpAnalysisTools 里，未注入会 NPE
        ReflectionTestUtils.setField(executor, "analysisTools", new ErpAnalysisTools());
        // §S32：query_knowledge_base 已拆到 ErpAnalysisTools，客户端改为两层注入
        ErpAnalysisTools analysisTools = new ErpAnalysisTools();
        ReflectionTestUtils.setField(analysisTools, "knowledgeSearchService", searchService);
        ReflectionTestUtils.setField(executor, "analysisTools", analysisTools);

        JsonObject json = JsonParser.parseString(
                executor.execute(call(1, "退货政策", 5))).getAsJsonObject();
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("命中 1 条"));
        assertEquals(1, json.getAsJsonObject("data").get("count").getAsInt());
        assertEquals("退货政策.pdf", json.getAsJsonObject("data")
                .getAsJsonArray("chunks").get(0).getAsJsonObject().get("filename").getAsString());
    }

    @Test
    @DisplayName("空 query 直接失败")
    void testBlankQueryFails() {
        UserContext.setUserId(1);
        UserContext.setShops(List.of(1L));
        ErpToolExecutor executor = new ErpToolExecutor();
        // §S32：query_knowledge_base 在 ErpAnalysisTools 里，未注入会 NPE
        ReflectionTestUtils.setField(executor, "analysisTools", new ErpAnalysisTools());
        JsonObject json = JsonParser.parseString(
                executor.execute(call(1, "  ", 5))).getAsJsonObject();
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("检索问题不能为空"));
    }

    @Test
    @DisplayName("服务未注入时降级失败")
    void testServiceNullFails() {
        UserContext.setUserId(1);
        UserContext.setShops(List.of(1L));
        ErpToolExecutor executor = new ErpToolExecutor();
        // §S32：query_knowledge_base 在 ErpAnalysisTools 里，未注入会 NPE
        ReflectionTestUtils.setField(executor, "analysisTools", new ErpAnalysisTools());
        JsonObject json = JsonParser.parseString(
                executor.execute(call(1, "退货政策", 5))).getAsJsonObject();
        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("message").getAsString().contains("知识库服务未启用"));
    }
}
