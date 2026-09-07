package com.amz.controller;

import com.amz.agent.eval.AgentEvalReport;
import com.amz.agent.eval.AgentEvalResult;
import com.amz.agent.eval.AgentEvalRunner;
import com.amz.agent.eval.LlmEvalScorer;
import com.amz.agent.langchain4j.LangChain4jAgentService;
import com.amz.mapper.AgentEvalLogMapper;
import com.amz.model.AgentEvalLog;
import com.amz.result.Result;
import com.amz.service.AiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiService aiService;

    @Autowired
    private LangChain4jAgentService langChain4jAgentService;

    @Autowired
    private AgentEvalRunner agentEvalRunner;

    @Autowired(required = false)
    private LlmEvalScorer llmEvalScorer;

    @Autowired
    private AgentEvalLogMapper agentEvalLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * LLM 双轨总开关（默认仅关键词轨，CI 可跑；耗 token 的 LLM 轨需显式开启）。
     * 对应环境变量 AGENT_LLM_EVAL_ENABLED=true。
     */
    @Value("${agent.llm-eval-enabled:false}")
    private boolean llmEvalEnabled;

    @PostMapping("/chat")
    public Result<String> chat(@RequestBody ChatRequest request) {
        String prompt = request.getPrompt();
        if (prompt == null || prompt.length() > 2000) {
            return Result.failure("prompt长度不能超过2000字符");
        }
        return aiService.chat(prompt);
    }

    @PostMapping("/agent/chat")
    public Result<String> agentChat(@RequestBody com.amz.model.dto.AgentChatDto request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return Result.failure("messages 不能为空");
        }
        return aiService.agentChat(request);
    }

    /**
     * ERP 运营 Agent（LangChain4j AiServices 编排）。
     * POST /ai/erp/agent?userId=1
     * Body: {"message":"最近7天订单情况如何？"}
     */
    @PostMapping("/erp/agent")
    public Result<String> erpAgent(
            @RequestParam(value = "userId", defaultValue = "1") Long userId,
            @RequestBody ErpAgentRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.failure("message 不能为空");
        }
        return langChain4jAgentService.chat(userId, request.getMessage());
    }

    /**
     * Agent 评测端点 - 运行全部 12 个标准用例回归测试。
     * POST /ai/eval/run?mode=keyword|both
     * <p>
     * 修改 prompt 后手动触发，验证 Agent 工具选择和响应质量未退化。
     * mode 缺省 keyword（与原来行为一致）；both 需开 AGENT_LLM_EVAL_ENABLED
     * 且配置 deepseek.api-key，否则返回失败。
     * 每次运行落库 amz_agent_eval_log（best-effort，失败不影响返回报告）。
     * <p>
     * 说明：规划案曾建议新建 AgentEvalController，但本端点已存在，
     * 为避免路径冲突与双入口漂移，直接扩展 mode 参数（缺省行为不变）。
     */
    @PostMapping("/eval/run")
    public Result<AgentEvalReport> runEval(@RequestParam(defaultValue = "keyword") String mode) {
        AgentEvalReport report;
        if ("both".equalsIgnoreCase(mode)) {
            if (!llmEvalEnabled || llmEvalScorer == null || !llmEvalScorer.isAvailable()) {
                return Result.failure("LLM 评估未启用（需 AGENT_LLM_EVAL_ENABLED=true 且配置 deepseek.api-key）");
            }
            report = agentEvalRunner.runAllWithLlm(llmEvalScorer);
        } else {
            report = agentEvalRunner.runAll();
        }
        persistEvalLog(mode, report);
        return Result.success(report);
    }

    /**
     * 评估运行落库（best-effort：落库失败仅记日志，不影响报告返回）。
     */
    private void persistEvalLog(String mode, AgentEvalReport report) {
        try {
            int llmCount = 0;
            if (report.getResults() != null) {
                for (AgentEvalResult r : report.getResults()) {
                    if (r != null && r.getLlmScore() != null && !r.getLlmScore().isScoreError()) {
                        llmCount++;
                    }
                }
            }
            AgentEvalLog logEntry = new AgentEvalLog();
            LocalDateTime now = LocalDateTime.now();
            logEntry.setRunTime(now);
            logEntry.setMode(mode);
            logEntry.setTotalCases(report.getTotalCases());
            logEntry.setPassedCount(report.getPassedCount());
            logEntry.setPassRate(report.getPassRate());
            logEntry.setLlmEvaluatedCount(llmCount);
            logEntry.setReportJson(objectMapper.writeValueAsString(report));
            logEntry.setCreateTime(now);
            agentEvalLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("评估运行落库失败", e);
        }
    }

    public static class ChatRequest {
        private String prompt;

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    public static class ErpAgentRequest {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

}