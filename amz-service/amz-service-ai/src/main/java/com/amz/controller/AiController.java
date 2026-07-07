package com.amz.controller;

import com.amz.agent.ErpAgentService;
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
     * ERP 运营 Agent 端点（Function Calling 编排）。
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