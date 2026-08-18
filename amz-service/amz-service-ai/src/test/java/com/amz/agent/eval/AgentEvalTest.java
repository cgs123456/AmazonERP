package com.amz.agent.eval;

import com.amz.agent.langchain4j.LangChain4jAgentService;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Agent 评测回归测试（纯 Mockito，不依赖外部 AI API）。
 * <p>
 * 通过 mock {@link LangChain4jAgentService} 的返回结果，
 * 验证 {@link AgentEvalRunner} 的评测聚合逻辑（关键词匹配、通过率统计、异常处理）。
 * <p>
 * 现改为 mock 测试，可在无 API Key 环境下验证评测框架本身的正确性。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 评测框架单元测试")
class AgentEvalTest {

    @Mock
    private LangChain4jAgentService langChain4jAgentService;

    @InjectMocks
    private AgentEvalRunner evalRunner;

    /**
     * 当 v2 Agent 回复包含全部关键词时，通过率应为 100%。
     * 验证：用例数 12、通过数 12、通过率 1.0、每个结果无缺失关键词。
     */
    @Test
    @DisplayName("v2 Agent 回复包含全部关键词 → 通过率 100%")
    void testV2AllPassWhenAgentReturnsAllKeywords() {
        when(langChain4jAgentService.chat(anyLong(), anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(1);
            AgentEvalCase evalCase = AgentEvalCases.all().stream()
                    .filter(c -> c.getQuestion().equals(question))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("未知用例: " + question));
            return Result.success(String.join("，", evalCase.getExpectedKeywords()));
        });

        AgentEvalReport report = evalRunner.runAll();

        assertNotNull(report, "评测报告不应为 null");
        assertEquals(12, report.getTotalCases(), "应有 12 个评测用例");
        assertEquals(12, report.getPassedCount(), "全部用例应通过");
        assertEquals(0, report.getFailedCount(), "不应有失败用例");
        assertEquals(1.0, report.getPassRate(), 0.0001, "通过率应为 100%");
        assertEquals("v2", report.getAgentVersion(), "Agent 版本应为 v2");
        assertEquals(12, report.getResults().size(), "结果列表大小应为 12");
        for (AgentEvalResult r : report.getResults()) {
            assertTrue(r.isPassed(), "用例 " + r.getCaseId() + " 应通过");
            assertTrue(r.getMissedKeywords().isEmpty(),
                    "用例 " + r.getCaseId() + " 不应有缺失关键词");
            assertNotNull(r.getActualResponse(), "用例 " + r.getCaseId() + " 应有实际回复");
        }
    }

    /**
     * 当 v2 Agent 回复为空字符串时，所有用例应失败（通过率 0%）。
     * 验证：关键词匹配逻辑能正确识别"回复未包含任何关键词"的情况。
     */
    @Test
    @DisplayName("v2 Agent 回复为空 → 通过率 0% 且全部关键词缺失")
    void testV2AllFailWhenAgentReturnsEmpty() {
        when(langChain4jAgentService.chat(anyLong(), anyString()))
                .thenReturn(Result.success(""));

        AgentEvalReport report = evalRunner.runAll();

        assertNotNull(report);
        assertEquals(12, report.getTotalCases());
        assertEquals(0, report.getPassedCount(), "空回复时不应有用例通过");
        assertEquals(12, report.getFailedCount(), "全部用例应失败");
        assertEquals(0.0, report.getPassRate(), 0.0001, "通过率应为 0%");
        for (AgentEvalResult r : report.getResults()) {
            assertFalse(r.isPassed(), "用例 " + r.getCaseId() + " 应失败");
            assertFalse(r.getMissedKeywords().isEmpty(),
                    "用例 " + r.getCaseId() + " 应有缺失关键词");
        }
    }

    /**
     * 当 v2 Agent 调用返回错误码（非 200）时，用例应判定为失败并记录错误信息。
     * 验证：Agent 调用失败时不会 NPE，而是正确标记为失败。
     */
    @Test
    @DisplayName("v2 Agent 调用返回错误 → 用例失败且记录错误信息")
    void testV2FailWhenAgentReturnsError() {
        when(langChain4jAgentService.chat(anyLong(), anyString()))
                .thenReturn(Result.failure("服务繁忙"));

        AgentEvalReport report = evalRunner.runAll();

        assertNotNull(report);
        assertEquals(12, report.getTotalCases());
        assertEquals(0, report.getPassedCount(), "Agent 调用失败时不应有通过用例");
        assertEquals(12, report.getFailedCount());
        assertEquals(0.0, report.getPassRate(), 0.0001);
        for (AgentEvalResult r : report.getResults()) {
            assertFalse(r.isPassed(), "用例 " + r.getCaseId() + " 应失败");
            assertNotNull(r.getErrorMessage(), "用例 " + r.getCaseId() + " 应记录错误信息");
            assertFalse(r.getMissedKeywords().isEmpty(),
                    "用例 " + r.getCaseId() + " 应标记全部关键词为缺失");
        }
    }

    /**
     * 默认 runAll() 应使用 v2 版本。
     */
    @Test
    @DisplayName("runAll() 默认使用 v2 版本")
    void testDefaultRunAllUsesV2() {
        when(langChain4jAgentService.chat(anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    String question = invocation.getArgument(1);
                    AgentEvalCase evalCase = AgentEvalCases.all().stream()
                            .filter(c -> c.getQuestion().equals(question))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("未知用例: " + question));
                    return Result.success(String.join("，", evalCase.getExpectedKeywords()));
                });

        AgentEvalReport report = evalRunner.runAll();

        assertNotNull(report);
        assertEquals("v2", report.getAgentVersion(), "默认应使用 v2 版本");
        assertEquals(12, report.getPassedCount());
    }
}
