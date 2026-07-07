package com.amz.service;

import com.amz.mapper.TranslationCacheMapper;
import com.amz.model.TranslationCache;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 翻译服务：基于 DeepSeek LLM 的电商文案翻译，三级缓存策略。
 * <ol>
 *   <li>计算原文 SHA-256 哈希</li>
 *   <li>查 MySQL amz_translation_cache（hash + sourceLang + targetLang）</li>
 *   <li>命中返回，未命中调用 DeepSeek API</li>
 *   <li>翻译成功写回缓存</li>
 *   <li>LLM 不可用 → 返回原文（降级）</li>
 * </ol>
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    @Value("${deepseek.api_url}")
    private String apiUrl;

    @Value("${deepseek.api_key}")
    private String apiKey;

    @Autowired
    private TranslationCacheMapper translationCacheMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson = new Gson();

    /**
     * 翻译文本（带缓存）。LLM 不可用时降级返回原文。
     *
     * @param sourceText 原文
     * @param sourceLang 源语言代码（如 en）
     * @param targetLang 目标语言代码（如 de）
     * @return 译文；调用失败时返回原文
     */
    public String translate(String sourceText, String sourceLang, String targetLang) {
        if (sourceText == null || sourceText.trim().isEmpty()) {
            return sourceText;
        }

        String hash = sha256(sourceText);

        // 1. 查缓存
        QueryWrapper<TranslationCache> qw = new QueryWrapper<>();
        qw.eq("source_text_hash", hash)
                .eq("source_lang", sourceLang)
                .eq("target_lang", targetLang);
        TranslationCache cached = translationCacheMapper.selectOne(qw);
        if (cached != null) {
            log.debug("Translation cache hit hash={} {}->{}", hash, sourceLang, targetLang);
            return cached.getTranslatedText();
        }

        // 2. 调用 DeepSeek
        try {
            String translated = callDeepSeek(sourceText, sourceLang, targetLang);

            // 3. 写回缓存
            TranslationCache cache = new TranslationCache();
            cache.setSourceTextHash(hash);
            cache.setSourceLang(sourceLang);
            cache.setTargetLang(targetLang);
            cache.setSourceText(sourceText);
            cache.setTranslatedText(translated);
            cache.setCreateTime(LocalDateTime.now());
            translationCacheMapper.insert(cache);

            log.info("Translation done {}->{} hash={} len={}", sourceLang, targetLang, hash,
                    translated.length());
            return translated;
        } catch (Exception e) {
            log.warn("DeepSeek translation failed, degrade to source text: {}", e.getMessage());
            return sourceText;
        }
    }

    /**
     * 调用 DeepSeek chat/completions 接口完成翻译。
     */
    private String callDeepSeek(String sourceText, String sourceLang, String targetLang) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "deepseek-chat");

        JsonArray messages = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content",
                "You are a professional e-commerce translator. Translate the following text from "
                        + sourceLang + " to " + targetLang
                        + ". Keep marketing tone. Output only the translation.");
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", sourceText);
        messages.add(systemMsg);
        messages.add(userMsg);
        requestBody.add("messages", messages);
        requestBody.addProperty("temperature", 0.3);
        requestBody.addProperty("max_tokens", 500);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("DeepSeek API returned status " + response.statusCode()
                    + " body=" + response.body());
        }

        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        return body.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }

    /**
     * 计算文本的 SHA-256 哈希（小写十六进制）。
     */
    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
