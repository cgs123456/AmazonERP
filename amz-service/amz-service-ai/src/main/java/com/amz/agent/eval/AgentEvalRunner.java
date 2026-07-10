package com.amz.agent.eval;

import com.amz.agent.ErpAgentService;
import com.amz.agent.langchain4j.LangChain4jAgentService;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent 评测运行器。
 * <p>
 * 执行流程：
 * <ol>
 *   <li>加载全部标准评测用例（AgentEvalCases.all()）</li>
 *   <li>逐个将 question 发送给 Agent（v1 手写或 v2 LangChain4j）</li>
 *   <li>检查 Agent 回复是否包含所有 expectedKeywords</li>
 *   <li>生成聚合报告 AgentEvalReport</li>
 * </ol>
 * <p>
 * 使用场景：
 * - 修改 prompt 后手动触发回归（REST 接口 /ai/eval/run）
 * - CI 中自动跑回归（JUnit 测试 AgentEvalTest，需配置 DEEPSEEK_API_KEY）
 */
@Slf4j
@Service
public class AgentEvalRunner {

    @Autowired
    private ErpAgentService erpAgentService;

    @Autowired
    private LangChain4jAgentService langChain4jAgentService;

    /**
     * 运行全部评测用例（默认使用 v2 LangChain4j Agent）。
     */
    public AgentEvalReport runAll() {
        return runAll("v2");
    }

    /**
     * 运行全部评测用例。
     *
     * @param agentVersion "v1" 使用手写编排，"v2" 使用 LangChain4j
     */
    public AgentEvalReport runAll(String agentVersion) {
        List<AgentEvalCase> cases = AgentEvalCases.all();
        List<AgentEvalResult> results = new ArrayList<>(cases.size());
        long totalStart = System.currentTimeMillis();

        log.info("===== Agent 评测开始（{}），共 {} 个用例 =====", agentVersion, cases.size());

        for (AgentEvalCase evalCase : cases) {
            AgentEvalResult result = runSingle(evalCase, agentVersion);
            results.add(result);

            String status = result.isPassed() ? "✓ PASS" : "✗ FAIL";
            log.info("{} [{}] {} | 耗时={}ms | 缺失关键词={}",
                    status, evalCase.getId(), evalCase.getDescription(),
                    result.getDurationMs(),
                    result.getMissedKeywords());
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        int passed = (int) results.stream().filter(AgentEvalResult::isPassed).count();
        int failed = cases.size() - passed;
        double passRate = cases.isEmpty() ? 0.0 : (double) passed / cases.size();

        log.info("===== 评测完成：{}/{} 通过，通过率 {}%，总耗时 {}ms =====",
                passed, cases.size(), String.format("%.1f", passRate * 100), totalDuration);

        return AgentEvalReport.builder()
                .timestamp(LocalDateTime.now())
                .totalCases(cases.size())
                .passedCount(passed)
                .failedCount(failed)
                .passRate(passRate)
                .totalDurationMs(totalDuration)
                .results(results)
                .agentVersion(agentVersion)
                .build();
    }

    /**
     * 运行单个评测用例。
     */
    private AgentEvalResult runSingle(AgentEvalCase evalCase, String agentVersion) {
        long start = System.currentTimeMillis();
        AgentEvalResult.AgentEvalResultBuilder builder = AgentEvalResult.builder()
                .caseId(evalCase.getId());

        try {
            // 调用 Agent
            Result<String> agentResult;
            if ("v1".equalsIgnoreCase(agentVersion)) {
                agentResult = erpAgentService.chat(evalCase.getQuestion());
            } else {
                agentResult = langChain4jAgentService.chat(evalCase.getQuestion());
            }

            long duration = System.currentTimeMillis() - start;
            builder.durationMs(duration);

            if (agentResult.getCode() != 200) {
                return builder
                        .passed(false)
                        .actualResponse(null)
                        .errorMessage("Agent 调用失败: " + agentResult.getMessage())
                        .matchedKeywords(List.of())
                        .missedKeywords(evalCase.getExpectedKeywords())
                        .build();
            }

            String response = agentResult.getData();
            builder.actualResponse(response);

            // 关键词匹配（不区分大小写）
            String responseLower = response.toLowerCase(Locale.ROOT);
            List<String> matched = new ArrayList<>();
            List<String> missed = new ArrayList<>();
            for (String keyword : evalCase.getExpectedKeywords()) {
                if (responseLower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matched.add(keyword);
                } else {
                    missed.add(keyword);
                }
            }

            return builder
                    .passed(missed.isEmpty())
                    .matchedKeywords(matched)
                    .missedKeywords(missed)
                    .build();

        } catch (Exception e) {
            log.error("评测用例 {} 执行异常", evalCase.getId(), e);
            return builder
                    .passed(false)
                    .durationMs(System.currentTimeMillis() - start)
                    .errorMessage(e.getMessage())
                    .matchedKeywords(List.of())
                    .missedKeywords(evalCase.getExpectedKeywords())
                    .build();
        }
    }
}
