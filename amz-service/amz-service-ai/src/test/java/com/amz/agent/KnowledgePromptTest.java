package com.amz.agent;

import com.amz.agent.langchain4j.ErpAgentInterface;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库 prompt 接线测试（B-1）。
 * <p>
 * 两条实际生效的提示词链路（LangChain4j @SystemMessage 与记忆化链路的
 * ErpSystemPromptBuilder）都必须声明 query_knowledge_base 工具与 SOP 优先规则，
 * 防止后续改提示词时悄悄丢掉 RAG 接线。纯字符串断言，无 Spring、无网络。
 */
@DisplayName("知识库 prompt 接线测试")
class KnowledgePromptTest {

    @Test
    @DisplayName("LangChain4j 系统提示词含知识库工具与 SOP 优先规则")
    void testLangChain4jPrompt() throws Exception {
        SystemMessage ann = ErpAgentInterface.class
                .getMethod("chat", String.class, String.class)
                .getAnnotation(SystemMessage.class);
        String prompt = String.join("\n", ann.value());
        assertTrue(prompt.contains("query_knowledge_base"), "必须声明知识库工具");
        assertTrue(prompt.contains("优先"), "必须声明 SOP 优先检索规则");
        assertTrue(prompt.contains("来源"), "必须要求注明文档来源");
    }

    @Test
    @DisplayName("ErpSystemPromptBuilder 含知识库工具、示例与 SOP 优先规则")
    void testLegacyPromptBuilder() {
        String prompt = new ErpSystemPromptBuilder().build();
        assertTrue(prompt.contains("query_knowledge_base"), "必须声明知识库工具");
        assertTrue(prompt.contains("示例11"), "必须保留 SOP 问答 Few-Shot 示例");
        assertTrue(prompt.contains("不得编造文档依据"), "必须声明无命中时不得编造");
    }
}
