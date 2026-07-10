package com.amz.controller;

import com.amz.agent.ErpAgentService;
import com.amz.agent.eval.AgentEvalReport;
import com.amz.agent.eval.AgentEvalRunner;
import com.amz.agent.langchain4j.LangChain4jAgentService;
import com.amz.result.Result;
import com.amz.service.AiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiService aiService;

    @Autowired
    private ErpAgentService erpAgentService;

    @Autowired
    private LangChain4jAgentService langChain4jAgentService;

    @Autowired
    private AgentEvalRunner agentEvalRunner;

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
     * ERP 运营 Agent 端点（手写 Function Calling 编排，旧版）。
     * POST /ai/erp/agent
     * Body: {"message":"最近7天订单情况如何？"}
     */
    @PostMapping("/erp/agent")
    public Result<String> erpAgent(@RequestBody ErpAgentRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.failure("message 不能为空");
        }
        return erpAgentService.chat(request.getMessage());
    }

    /**
     * ERP 运营 Agent 端点（LangChain4j AiServices 编排，新版）。
     * POST /ai/erp/agent/v2
     * Body: {"message":"最近7天订单情况如何？"}
     * <p>
     * 使用 LangChain4j 原生 Function Calling 替代手写循环 + 正则解析 JSON，
     * 工具调用准确率更高，代码量减少约 60%。
     */
    @PostMapping("/erp/agent/v2")
    public Result<String> erpAgentV2(@RequestBody ErpAgentRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.failure("message 不能为空");
        }
        return langChain4jAgentService.chat(request.getMessage());
    }

    /**
     * Agent 评测端点 - 运行全部 12 个标准用例回归测试。
     * POST /ai/eval/run?version=v2
     * <p>
     * 修改 prompt 后手动触发，验证 Agent 工具选择和响应质量未退化。
     */
    @PostMapping("/eval/run")
    public Result<AgentEvalReport> runEval(
            @RequestParam(value = "version", defaultValue = "v2") String version) {
        AgentEvalReport report = agentEvalRunner.runAll(version);
        return Result.success(report);
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