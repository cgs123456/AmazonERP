package com.amz.agent.eval;

import lombok.Data;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 评测聚合报告。
 */
@Data
@Builder
public class AgentEvalReport {

    /**
     * 测试时间
     */
    private LocalDateTime timestamp;

    /**
     * 总用例数
     */
    private int totalCases;

    /**
     * 通过数
     */
    private int passedCount;

    /**
     * 失败数
     */
    private int failedCount;

    /**
     * 通过率（0.0 - 1.0）
     */
    private double passRate;

    /**
     * 总耗时（毫秒）
     */
    private long totalDurationMs;

    /**
     * 各用例结果
     */
    private List<AgentEvalResult> results;

    /**
     * 使用的 Agent 版本（v1 手写 / v2 LangChain4j）
     */
    private String agentVersion;
}
