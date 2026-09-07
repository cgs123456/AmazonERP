package com.amz.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单用例 LLM 四指标评分（0-1，保留 2 位小数）。
 * <p>
 * {@code scoreError=true} 表示评分器本身失败（模型未启用/输出解析失败），
 * 此时四个分数无意义，调用方不得计入聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmEvalScore {

    /** 忠实度：回答忠实于可验证数据，无编造数字/事实 */
    private double faithfulness;

    /** 回答相关性：切题，解决用户问题 */
    private double answerRelevancy;

    /** 工具选择准确度：回答体现期望工具的能力 */
    private double toolSelectionAccuracy;

    /** 完整性：覆盖问题的关键方面，无明显遗漏 */
    private double completeness;

    /** 评分器失败标记 */
    private boolean scoreError;

    /** 评分器失败原因（scoreError=true 时填写） */
    private String errorMessage;

    public static LlmEvalScore error(String message) {
        return LlmEvalScore.builder()
                .faithfulness(0)
                .answerRelevancy(0)
                .toolSelectionAccuracy(0)
                .completeness(0)
                .scoreError(true)
                .errorMessage(message)
                .build();
    }
}
