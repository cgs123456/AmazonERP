package com.amz.agent.langchain4j;

import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * LangChain4j Agent 服务（替代 ErpAgentService 手写编排）。
 * <p>
 * 核心差异对比：
 * <table border="1">
 * <tr><th></th><th>旧版 ErpAgentService</th><th>本类 LangChain4jAgentService</th></tr>
 * <tr><td>编排方式</td><td>手写 for 循环 5 轮</td><td>AiServices 自动编排（最多 10 轮）</td></tr>
 * <tr><td>工具调用</td><td>正则解析 LLM 输出 JSON</td><td>原生 Function Calling（tool_calls 字段）</td></tr>
 * <tr><td>工具注册</td><td>switch 硬编码 12 个 case</td><td>@Tool 注解自动扫描注册</td></tr>
 * <tr><td>消息管理</td><td>手动追加 assistant/user 消息</td><td>ChatMemory 自动管理上下文窗口</td></tr>
 * <tr><td>代码量</td><td>~144 行（含 parseFunctionCall/extractJson）</td><td>~40 行（委托给 AiServices）</td></tr>
 * </table>
 */
@Slf4j
@Service
public class LangChain4jAgentService {

    @Autowired
    private ErpAgentInterface erpAgent;

    /**
     * 执行 Agent 对话（LangChain4j 编排）。
     *
     * @param userMessage 用户输入
     * @return Agent 最终回复
     */
    public Result<String> chat(String userMessage) {
        try {
            String response = erpAgent.chat(userMessage);
            return Result.success(response);
        } catch (Exception e) {
            log.error("LangChain4j Agent 调用失败", e);
            return Result.failure("Agent 调用失败: " + e.getMessage());
        }
    }
}
