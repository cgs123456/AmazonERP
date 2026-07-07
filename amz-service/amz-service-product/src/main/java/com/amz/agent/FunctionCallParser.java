package com.amz.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.amz.agent.dto.FunctionCall;
import com.amz.constant.RedisConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 LLM 文本输出为 FunctionCall，并统计 JSON 格式正确率。
 *
 * 解析规则：
 * 1. 尝试从文本中提取 ```json ... ``` 代码块
 * 2. 若无代码块，尝试直接解析整段为 JSON
 * 3. JSON 必须包含 name（字符串）和 arguments（对象）字段
 *
 * 格式正确率 = success / total，记录在 Redis hash AGENT_FORMAT_STATS
 */
@Component
@Slf4j
public class FunctionCallParser {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Gson gson = new Gson();

    /** 匹配 ```json ... ``` 代码块 */
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    /** 匹配裸 ``` ... ``` 代码块 */
    private static final Pattern BARE_BLOCK = Pattern.compile("```\\s*([\\s\\S]*?)```");

    /**
     * 解析 LLM 输出文本。
     * @param text LLM 原始输出
     * @return 解析成功返回 FunctionCall；解析失败（不是函数调用）返回 null
     */
    public FunctionCall parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String jsonStr = extractJson(text);
        if (jsonStr == null) {
            // 不是 JSON，说明是自然语言回复，不算格式错误（只是不是函数调用）
            return null;
        }

        // 是 JSON 但可能格式不对 —— 计入统计
        incrementTotal();
        FunctionCall call = tryParseFunctionCall(jsonStr);
        if (call != null) {
            incrementSuccess();
            log.debug("FunctionCall 解析成功: {}", call.getName());
        } else {
            log.warn("FunctionCall 格式错误，原始 JSON: {}", jsonStr);
        }
        return call;
    }

    /** 从文本中提取 JSON 字符串 */
    private String extractJson(String text) {
        // 1. 尝试 ```json 块
        Matcher m = JSON_BLOCK.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 2. 尝试裸 ``` 块
        m = BARE_BLOCK.matcher(text);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (candidate.startsWith("{")) {
                return candidate;
            }
        }
        // 3. 尝试整段（去除首尾空白）
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return null;
    }

    private FunctionCall tryParseFunctionCall(String jsonStr) {
        try {
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            if (obj == null || !obj.has("name") || !obj.has("arguments")) {
                return null;
            }
            FunctionCall call = new FunctionCall();
            call.setName(obj.get("name").getAsString());
            // arguments 转为 Map
            Map<String, Object> args = new HashMap<>();
            JsonObject argsObj = obj.getAsJsonObject("arguments");
            for (String key : argsObj.keySet()) {
                args.put(key, gson.fromJson(argsObj.get(key), Object.class));
            }
            call.setArguments(args);
            return call;
        } catch (JsonSyntaxException e) {
            return null;
        } catch (Exception e) {
            log.error("FunctionCall 解析异常", e);
            return null;
        }
    }

    private void incrementTotal() {
        redisTemplate.opsForHash().increment(RedisConstant.AGENT_FORMAT_STATS, "total", 1);
    }

    private void incrementSuccess() {
        redisTemplate.opsForHash().increment(RedisConstant.AGENT_FORMAT_STATS, "success", 1);
    }

    /**
     * 获取当前格式正确率
     * @return [successCount, totalCount]
     */
    public long[] getStats() {
        Object success = redisTemplate.opsForHash().get(RedisConstant.AGENT_FORMAT_STATS, "success");
        Object total = redisTemplate.opsForHash().get(RedisConstant.AGENT_FORMAT_STATS, "total");
        long s = success == null ? 0 : Long.parseLong(success.toString());
        long t = total == null ? 0 : Long.parseLong(total.toString());
        return new long[]{s, t};
    }

    /**
     * 判断文本是否"看起来像函数调用尝试"（包含 JSON 但解析失败）。
     * 用于区分"自然语言回复"和"格式错误的函数调用"，前者为最终答案，后者需记录失败样本并重试。
     */
    public boolean looksLikeFunctionCallAttempt(String text) {
        return text != null && !text.trim().isEmpty() && extractJson(text) != null;
    }
}
