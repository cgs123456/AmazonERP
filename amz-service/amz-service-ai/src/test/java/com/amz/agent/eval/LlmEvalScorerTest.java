package com.amz.agent.eval;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 评估打分器单元测试（模型全 mock，不耗 token、不依赖网络）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLM 评估打分器测试")
class LlmEvalScorerTest {

    @Mock
    private ChatLanguageModel scoringModel;

    @InjectMocks
    private LlmEvalScorer scorer;

    private AgentEvalCase demoCase() {
        return AgentEvalCase.builder()
                .id("EVAL-T1")
                .description("demo")
                .question("最近7天订单如何？")
                .expectedKeywords(List.of("订单"))
                .expectedToolName("query_orders")
                .build();
    }

    @Test
    @DisplayName("合法 JSON 应解析出四指标且不过 error")
    void testValidJsonParsed() {
        when(scoringModel.generate(anyString())).thenReturn(
                "{\"faithfulness\":0.85,\"answer_relevancy\":0.9,"
                        + "\"tool_selection_accuracy\":0.8,\"completeness\":0.75}");

        LlmEvalScore score = scorer.score(demoCase(), "订单共 10 单");

        assertFalse(score.isScoreError());
        assertEquals(0.85, score.getFaithfulness());
        assertEquals(0.9, score.getAnswerRelevancy());
        assertEquals(0.8, score.getToolSelectionAccuracy());
        assertEquals(0.75, score.getCompleteness());
    }

    @Test
    @DisplayName("带 ```json fences 的输出应能解析")
    void testFencedJsonParsed() {
        when(scoringModel.generate(anyString())).thenReturn(
                "```json\n{\"faithfulness\":1,\"answer_relevancy\":1,"
                        + "\"tool_selection_accuracy\":1,\"completeness\":1}\n```");

        LlmEvalScore score = scorer.score(demoCase(), "ok");

        assertFalse(score.isScoreError());
        assertEquals(1.0, score.getFaithfulness());
    }

    @Test
    @DisplayName("越界分值应钳制到 [0,1]")
    void testOutOfRangeClamped() {
        when(scoringModel.generate(anyString())).thenReturn(
                "{\"faithfulness\":2.5,\"answer_relevancy\":-1,"
                        + "\"tool_selection_accuracy\":0.5,\"completeness\":0.5}");

        LlmEvalScore score = scorer.score(demoCase(), "ok");

        assertFalse(score.isScoreError());
        assertEquals(1.0, score.getFaithfulness());
        assertEquals(0.0, score.getAnswerRelevancy());
    }

    @Test
    @DisplayName("垃圾输出应重试 1 次后标记 score_error")
    void testGarbageMarksScoreErrorAfterRetry() {
        when(scoringModel.generate(anyString())).thenReturn("not json at all");

        LlmEvalScore score = scorer.score(demoCase(), "ok");

        assertTrue(score.isScoreError());
        // 初次 + 重试共 2 次调用
        verify(scoringModel, times(2)).generate(anyString());
    }

    @Test
    @DisplayName("模型未启用（null）应直接返回 score_error 且不调用")
    void testNullModelReturnsError() {
        LlmEvalScorer noModel = new LlmEvalScorer();

        LlmEvalScore score = noModel.score(demoCase(), "ok");

        assertTrue(score.isScoreError());
    }

    @Test
    @DisplayName("prompt 应包含问题与期望工具（可审查性）")
    void testPromptContainsCase() {
        when(scoringModel.generate(anyString())).thenReturn(
                "{\"faithfulness\":0.5,\"answer_relevancy\":0.5,"
                        + "\"tool_selection_accuracy\":0.5,\"completeness\":0.5}");

        scorer.score(demoCase(), "ok");

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(scoringModel).generate(captor.capture());
        assertTrue(captor.getValue().contains("最近7天订单如何？"));
        assertTrue(captor.getValue().contains("query_orders"));
    }
}
