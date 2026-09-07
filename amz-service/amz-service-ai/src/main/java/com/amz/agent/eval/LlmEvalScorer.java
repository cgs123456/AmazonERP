package com.amz.agent.eval;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * LLM 评估打分器：用大模型对 Agent 回复做四指标量化评分。
 * <p>
 * 与关键词轨互补：关键词轨回答"有没有提到"，LLM 轨回答"答得好不好"。
 * <p>
 * 可靠性设计：
 * <ul>
 *   <li>模型 Bean 缺失（mock profile / 未配 key）→ 直接返回 scoreError，不抛异常；</li>
 *   <li>输出解析失败 → 重试 1 次，再失败标记 score_error（不记 0 分，避免评分器故障污染结果）；</li>
 *   <li>本方法永不抛异常，调用方可无条件调用。</li>
 * </ul>
 */
@Slf4j
@Service
public class LlmEvalScorer {

    @Autowired(required = false)
    private ChatLanguageModel scoringModel;

    public boolean isAvailable() {
        return scoringModel != null;
    }

    /**
     * 对单条回复打分（永不抛异常）。
     */
    public LlmEvalScore score(AgentEvalCase evalCase, String response) {
        if (scoringModel == null) {
            return LlmEvalScore.error("LLM 评分模型未启用（缺 deepseek.api-key）");
        }
        if (evalCase == null || response == null) {
            return LlmEvalScore.error("评分输入为空");
        }
        String prompt = buildPrompt(evalCase, response);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String text = scoringModel.generate(prompt);
                LlmEvalScore parsed = parseScores(text);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("LLM 评分输出解析失败（第{}次）caseId={}", attempt + 1, evalCase.getId());
            } catch (Exception e) {
                log.warn("LLM 评分调用失败（第{}次）caseId={}", attempt + 1, evalCase.getId(), e);
            }
        }
        return LlmEvalScore.error("LLM 评分解析失败，已重试 1 次");
    }

    private String buildPrompt(AgentEvalCase evalCase, String response) {
        String resp = response.length() > 2000 ? response.substring(0, 2000) : response;
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是电商运营助手回答质量评审员。对以下问答按4个维度打0-1分（保留2位小数），只输出JSON，不要解释。\n");
        sb.append("问题：").append(evalCase.getQuestion()).append("\n");
        sb.append("期望关键词：").append(evalCase.getExpectedKeywords()).append("\n");
        sb.append("期望调用工具：").append(evalCase.getExpectedToolName() == null ? "无" : evalCase.getExpectedToolName()).append("\n");
        sb.append("助手回答：").append(resp).append("\n");
        sb.append("维度定义：faithfulness=忠实于可验证数据无编造；answer_relevancy=切题解惑；");
        sb.append("tool_selection_accuracy=回答体现期望工具的能力；completeness=覆盖关键方面无遗漏。\n");
        sb.append("输出示例：{\"faithfulness\":0.85,\"answer_relevancy\":0.9,");
        sb.append("\"tool_selection_accuracy\":0.8,\"completeness\":0.75}");
        return sb.toString();
    }

    /**
     * 解析模型输出为分数；返回 null 表示解析失败（调用方重试）。
     * 兼容模型套 ```json fences 的情况；分值钳制到 [0,1]。
     */
    private LlmEvalScore parseScores(String text) {
        if (text == null) {
            return null;
        }
        String json = text.replace("```json", "").replace("```", "").trim();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return LlmEvalScore.builder()
                .faithfulness(clamp(obj.get("faithfulness").getAsDouble()))
                .answerRelevancy(clamp(obj.get("answer_relevancy").getAsDouble()))
                .toolSelectionAccuracy(clamp(obj.get("tool_selection_accuracy").getAsDouble()))
                .completeness(clamp(obj.get("completeness").getAsDouble()))
                .scoreError(false)
                .build();
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.min(1.0, Math.max(0.0, v));
    }
}
