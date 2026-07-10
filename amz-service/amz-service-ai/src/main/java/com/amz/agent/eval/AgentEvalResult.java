package com.amz.agent.eval;

import lombok.Data;
import lombok.Builder;

import java.util.List;

/**
 * 单个评测用例的执行结果。
 */
@Data
@Builder
public class AgentEvalResult {

    private String caseId;

    /**
     * 是否通过（所有 expectedKeywords 均在回复中出现）
     */
    private boolean passed;

    /**
     * Agent 实际回复
     */
    private String actualResponse;

    /**
     * 匹配到的关键词
     */
    private List<String> matchedKeywords;

    /**
     * 未匹配到的关键词
     */
    private List<String> missedKeywords;

    /**
     * 执行耗时（毫秒）
     */
    private long durationMs;

    /**
     * 错误信息（Agent 调用失败时填写）
     */
    private String errorMessage;
}
