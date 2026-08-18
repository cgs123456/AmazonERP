package com.amz.service;

import com.amz.mapper.TranslationCacheMapper;
import com.amz.model.TranslationCache;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 翻译服务：基于 DeepSeek LLM 的电商文案翻译，三级缓存策略。
 * <ol>
 *   <li>L1 本地缓存（Caffeine）：进程内内存，毫秒级命中，TTL 10min；</li>
 *   <li>L2 分布式缓存（Redis / Redisson）：跨实例共享，TTL 1h；</li>
 *   <li>L3 持久化缓存（MySQL amz_translation_cache）：按 hash+语言对落库；</li>
 *   <li>三级均未命中 → 调用 DeepSeek API，成功写回 L3→L2→L1；</li>
 *   <li>LLM 不可用 → 返回原文（降级，不写缓存）。</li>
 * </ol>
 * Redis 不可用时自动跳过 L2，不影响主链路。
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    /** L1 本地缓存：最大 1 万条，写入后 10 分钟过期。 */
    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /** L2 Redis 缓存键前缀与过期时间。 */
    private static final String REDIS_KEY_PREFIX = "amz:trans:";
    private static final long REDIS_TTL_MINUTES = 60;

    @Value("${deepseek.api_url}")
    private String apiUrl;

    @Value("${deepseek.api_key}")
    private String apiKey;

    @Autowired
    private TranslationCacheMapper translationCacheMapper;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson = new Gson();

    /**
     * 翻译文本（带三级缓存）。LLM 不可用时降级返回原文。
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
        String key = REDIS_KEY_PREFIX + hash + ":" + sourceLang + ":" + targetLang;

        // L1 本地缓存
        String l1 = localCache.getIfPresent(key);
        if (l1 != null) {
            log.debug("L1 cache hit {}->{} hash={}", sourceLang, targetLang, hash);
            return l1;
        }

        // L2 Redis
        String l2 = readFromRedis(key);
        if (l2 != null) {
            localCache.put(key, l2);
            return l2;
        }

        // L3 MySQL
        QueryWrapper<TranslationCache> qw = new QueryWrapper<>();
        qw.eq("source_text_hash", hash)
                .eq("source_lang", sourceLang)
                .eq("target_lang", targetLang);
        TranslationCache cached = translationCacheMapper.selectOne(qw);
        if (cached != null) {
            log.debug("L3 cache hit {}->{} hash={}", sourceLang, targetLang, hash);
            writeBack(key, cached.getTranslatedText());
            return cached.getTranslatedText();
        }

        // 三级均未命中 → 调用 DeepSeek
        try {
            String translated = callDeepSeek(sourceText, sourceLang, targetLang);

            // 写回 L3（持久化）
            TranslationCache cache = new TranslationCache();
            cache.setSourceTextHash(hash);
            cache.setSourceLang(sourceLang);
            cache.setTargetLang(targetLang);
            cache.setSourceText(sourceText);
            cache.setTranslatedText(translated);
            cache.setCreateTime(LocalDateTime.now());
            translationCacheMapper.insert(cache);

            // 写回 L2 / L1
            writeBack(key, translated);

            log.info("Translation done {}->{} hash={} len={}", sourceLang, targetLang, hash,
                    translated.length());
            return translated;
        } catch (Exception e) {
            log.warn("DeepSeek translation failed, degrade to source text: {}", e.getMessage());
            return sourceText;
        }
    }

    /**
     * 写回 L2（Redis）与 L1（本地）。
     */
    private void writeBack(String key, String translated) {
        localCache.put(key, translated);
        writeToRedis(key, translated);
    }

    private String readFromRedis(String key) {
        if (redissonClient == null) {
            return null;
        }
        try {
            org.redisson.api.RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
            return bucket.get();
        } catch (Exception e) {
            log.warn("Redis read failed, skip L2: {}", e.getMessage());
            return null;
        }
    }

    private void writeToRedis(String key, String value) {
        if (redissonClient == null) {
            return;
        }
        try {
            redissonClient.getBucket(key, StringCodec.INSTANCE)
                    .set(value, REDIS_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis write failed, skip L2: {}", e.getMessage());
        }
    }

    /**
     * 调用 DeepSeek chat/completions 接口完成翻译。
     */
    private String callDeepSeek(String sourceText, String sourceLang, String targetLang) throws IOException, InterruptedException {
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
