package com.amz.agent.langchain4j;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * ERP 运营 Agent 接口（LangChain4j @AiService）。
 * <p>
 * LangChain4j AiServices 会自动处理：
 * <ul>
 *   <li>将 ErpTools 中的 @Tool 方法注册为 LLM 可调用的函数</li>
 *   <li>LLM 返回 tool_calls 时自动执行对应工具并将结果注入对话</li>
 *   <li>多轮工具调用编排（默认最多 10 轮）</li>
 *   <li>原生 Function Calling（DeepSeek/OpenAI tool_calls 字段，非文本 JSON 解析）</li>
 * </ul>
 * <p>
 * 优势：
 * - 消除了手写编排循环与 JSON 解析的样板代码
 * - 使用原生 Function Calling，工具调用准确率显著提升
 */
public interface ErpAgentInterface {

    @SystemMessage("""
            你是一个跨境电商 Amazon ERP 运营助手。你可以使用以下工具来回答用户的运营问题。

            可用工具：
            - 基础数据查询：query_orders / query_inventory / query_sales / query_profit / suggest_replenish
            - 库存健康度：check_inventory_health
            - 跨站点运营：cross_marketplace_listing / translate_listing
            - 广告与竞品：analyze_ad_performance / monitor_competitor_price
            - 费用与促销：estimate_fba_fees / generate_promotion_plan
            - 店铺知识库：query_knowledge_base（SOP / 政策 / 操作规范文档检索）

            知识库问答规则（SOP 优先）：
            - 当用户问题涉及 SOP、政策、流程、操作规范（如“退货政策是怎样的”“新品上架流程”）时，
              必须优先调用 query_knowledge_base(shopId, query) 检索店铺知识库，再基于检索片段作答
            - 引用知识库内容作答时必须注明来源文档名（如“依据《退货政策.pdf》”）
            - 若检索无命中，明确告知用户知识库暂无相关内容，再用通用知识简要回答，不得编造文档依据

            通用规则：
            - 每次只调用一个工具
            - 收到工具结果后，用自然语言总结并回答用户
            - 如果工具结果足够回答，直接给出结论，不再调用工具
            - 如果用户未指定 shopId，默认使用 1
            - 回答应简洁专业，包含关键数据和结论
            """)
    String chat(@MemoryId String sessionId, @UserMessage String message);
}