package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 评估运行日志实体（A-2 双轨评估落库）。
 */
@Data
@TableName("amz_agent_eval_log")
public class AgentEvalLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 运行时间 */
    private LocalDateTime runTime;

    /** 评估模式：keyword/both */
    private String mode;

    /** 总用例数 */
    private Integer totalCases;

    /** 通过数（关键词轨） */
    private Integer passedCount;

    /** 通过率 0-1 */
    private Double passRate;

    /** 完成 LLM 评分的用例数 */
    private Integer llmEvaluatedCount;

    /** 完整报告 JSON */
    private String reportJson;

    /** 创建时间 */
    private LocalDateTime createTime;
}
