package com.amz.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.amz.constant.RedisConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Few-Shot 样本池（Redis）。
 * - 成功样本：可正确解析为 FunctionCall 的 (query, output) 对
 * - 失败样本：格式错误的 (query, output) 对
 * - 成功样本最多保留 50 条，用于注入 system prompt 提升格式正确率
 */
@Component
@Slf4j
public class FewShotStore {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Gson gson = new Gson();

    private static final int MAX_SUCCESS_SAMPLES = 50;
    private static final int MAX_FAILURE_SAMPLES = 20;

    /**
     * 记录一条样本
     * @param query 用户原始问题
     * @param output LLM 原始输出
     * @param success 是否成功解析为 FunctionCall
     */
    public void recordSample(String query, String output, boolean success) {
        try {
            JsonObject sample = new JsonObject();
            sample.addProperty("query", query);
            sample.addProperty("output", output);
            String json = gson.toJson(sample);

            String key = success
                    ? RedisConstant.AGENT_FEWSHOT_SUCCESS
                    : RedisConstant.AGENT_FEWSHOT_FAILURE;

            // 左插（最新的在前面）
            redisTemplate.opsForList().leftPush(key, json);
            // 修剪保留最近 N 条
            redisTemplate.opsForList().trim(key, 0, success ? MAX_SUCCESS_SAMPLES - 1 : MAX_FAILURE_SAMPLES - 1);
        } catch (Exception e) {
            log.error("记录 Few-Shot 样本失败", e);
        }
    }

    /**
     * 获取最近 N 条成功样本（用于注入 system prompt）
     */
    public List<String> getSuccessSamples(int limit) {
        List<String> result = new ArrayList<>();
        try {
            List<Object> raw = redisTemplate.opsForList()
                    .range(RedisConstant.AGENT_FEWSHOT_SUCCESS, 0, limit - 1);
            if (raw == null) {
                return result;
            }
            for (Object o : raw) {
                result.add(o.toString());
            }
        } catch (Exception e) {
            log.error("读取 Few-Shot 样本失败", e);
        }
        return result;
    }
}
