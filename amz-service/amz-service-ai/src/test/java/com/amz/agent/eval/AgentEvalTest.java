package com.amz.agent.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 评测回归测试。
 * <p>
 * 仅在配置了 DEEPSEEK_API_KEY 环境变量时运行（CI 无 API Key 时自动跳过）。
 * <p>
 * 通过标准：
 * - 通过率 >= 75%（9/12），允许 LLM 在个别用例上回复风格偏差
 * - 所有用例均不应抛出未捕获异常
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class AgentEvalTest {

    @Autowired
    private AgentEvalRunner evalRunner;

    @Test
    void testLangChain4jAgentPassRate() {
        AgentEvalReport report = evalRunner.runAll("v2");

        assertNotNull(report);
        assertTrue(report.getTotalCases() == 12, "应有 12 个评测用例");

        // 通过率 >= 75%
        assertTrue(report.getPassRate() >= 0.75,
                "LangChain4j Agent 通过率应 >= 75%，实际: "
                        + String.format("%.1f%%", report.getPassRate() * 100)
                        + "（通过 " + report.getPassedCount() + "/" + report.getTotalCases() + "）");

        // 打印详细报告
        System.out.println("\n===== Agent 评测报告 =====");
        System.out.printf("通过率: %.1f%% (%d/%d)%n",
                report.getPassRate() * 100, report.getPassedCount(), report.getTotalCases());
        System.out.printf("总耗时: %dms%n", report.getTotalDurationMs());
        System.out.println("Agent 版本: " + report.getAgentVersion());
        for (AgentEvalResult r : report.getResults()) {
            String status = r.isPassed() ? "PASS" : "FAIL";
            System.out.printf("  [%s] %s | %dms | 缺失: %s%n",
                    status, r.getCaseId(), r.getDurationMs(), r.getMissedKeywords());
        }
    }
}
