package com.amz.agent.eval;

import lombok.Data;
import lombok.Builder;

import java.util.List;

/**
 * Agent 评测用例定义。
 * <p>
 * 每个用例描述一个标准问题及其期望结果：
 * - question：用户输入（模拟真实运营问题）
 * - expectedKeywords：Agent 回复中应包含的关键词（用于验证响应质量）
 * - expectedToolName：期望调用的工具名（用于验证工具选择准确性，可为 null 表示不校验）
 * - category：用例分类（ORDER / INVENTORY / AD / PROFIT / LOGISTICS / PROMOTION）
 */
@Data
@Builder
public class AgentEvalCase {

    /**
     * 用例 ID
     */
    private String id;

    /**
     * 用例描述
     */
    private String description;

    /**
     * 分类
     */
    private String category;

    /**
     * 用户输入（模拟真实运营问题）
     */
    private String question;

    /**
     * Agent 回复中应包含的关键词（不区分大小写）
     */
    private List<String> expectedKeywords;

    /**
     * 期望调用的工具名（可为 null 表示不校验工具选择）
     */
    private String expectedToolName;
}
