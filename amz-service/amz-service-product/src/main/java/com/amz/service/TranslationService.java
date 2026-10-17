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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    @Value("${deepseek.api-url}")
    private String apiUrl;

    @Value("${deepseek.api-key}")
    private String apiKey;

    @Value("${deepseek.model-name:deepseek-chat}")
    private String modelName;

    @Value("${deepseek.temperature:0.7}")
    private double temperature;

    @Value("${deepseek.timeout:60}")
    private long timeoutSeconds;

    @Autowired
    private TranslationCacheMapper translationCacheMapper;

    /**
     * L2 翻译缓存。原用 Redisson RBucket；Redisson starter 已整体移除
     * （官方未支持 Spring Boot 4，prod 启动即 ClassNotFoundException，容器实测），
     * 改用 StringRedisTemplate 读写，行为语义（TTL + 容错跳过）不变。
     */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * HTTP 客户端。**惰性创建**：{@code HttpClient.newBuilder().build()} 在
     * 部分环境（含本机 Windows）会因 loopback 不可用而抛 IOException（§S30 已定案）。
     * 在字段初始化时创建，等于让「这个 Bean 能否被构造」依赖于运行环境的网络栈——
     * 而翻译是本服务的一个可选能力，不该因此拖垮整个上下文启动。
     */
    private HttpClient httpClient;

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return httpClient;
    }

    private final Gson gson = new Gson();

    /**
     * 翻译文本（带三级缓存）。LLM 不可用时降级返回原文。
     *
     * <p><b>调用方注意</b>：本方法**无法告诉调用方是否发生了降级**——失败时返回的
     * 原文与「翻译后恰好相同」在返回值上不可区分。凡是需要如实汇报结果的链路
     * （Agent 工具、跨站复制）必须用 {@link #translateWithOutcome}。
     * 本方法保留给「拿到什么用什么」的旧调用方。
     *
     * @param sourceText 原文
     * @param sourceLang 源语言代码（如 en）
     * @param targetLang 目标语言代码（如 de）
     * @return 译文；调用失败时返回原文
     */
    public String translate(String sourceText, String sourceLang, String targetLang) {
        return translateWithOutcome(sourceText, sourceLang, targetLang).text();
    }

    /**
     * 翻译结果 + 是否降级。
     *
     * @param text     译文（降级时为原文）
     * @param degraded 是否因 LLM 调用失败而降级
     */
    public record TranslationOutcome(String text, boolean degraded) {
    }

    /**
     * 翻译文本，并如实报告是否降级。
     *
     * <p><b>为什么需要这个方法</b>：§S33.26 实测——`callDeepSeek` 失败时本服务
     * 静默返回原文，而 Agent 工具的 message 固定写「已将 X 文本翻译为 Y」。
     * 运营拿到的是未翻译的德文 Listing 标题，却以为翻译成功了。
     * 缓存命中（L1/L2/L3）不算降级：那是有译文的结果。
     */
    public TranslationOutcome translateWithOutcome(String sourceText, String sourceLang, String targetLang) {
        if (sourceText == null || sourceText.trim().isEmpty()) {
            return new TranslationOutcome(sourceText, false);
        }

        String hash = sha256(sourceText);
        String key = REDIS_KEY_PREFIX + hash + ":" + sourceLang + ":" + targetLang;

        // L1 本地缓存
        String l1 = localCache.getIfPresent(key);
        if (l1 != null) {
            log.debug("L1 cache hit {}->{} hash={}", sourceLang, targetLang, hash);
            return new TranslationOutcome(l1, false);
        }

        // L2 Redis
        String l2 = readFromRedis(key);
        if (l2 != null) {
            localCache.put(key, l2);
            return new TranslationOutcome(l2, false);
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
            return new TranslationOutcome(cached.getTranslatedText(), false);
        }

        // 三级均未命中 → 调用 DeepSeek。
        // 只有「拿译文」这一步失败才降级返回原文；写缓存失败必须单独处理（见下），
        // 否则并发撞唯一键这类缓存问题会连译文一起丢掉。
        String translated;
        try {
            translated = callDeepSeek(sourceText, sourceLang, targetLang);
        } catch (Exception e) {
            log.warn("DeepSeek translation failed, degrade to source text: {}", e.getMessage());
            return new TranslationOutcome(sourceText, true);
        }

        // 写回 L3（持久化）。缓存是加速层，不是正确性的一部分：
        // 落库失败（最典型是并发下同一 hash 撞唯一键 uk_hash_langs）只降级为「本次不缓存」，
        // 绝不能让已经成功拿到的译文退回原文——那等于用户付了一次 LLM 调用却拿到未翻译文本。
        try {
            TranslationCache cache = new TranslationCache();
            cache.setSourceTextHash(hash);
            cache.setSourceLang(sourceLang);
            cache.setTargetLang(targetLang);
            cache.setSourceText(sourceText);
            cache.setTranslatedText(translated);
            cache.setCreateTime(LocalDateTime.now());
            translationCacheMapper.insert(cache);
        } catch (Exception e) {
            log.warn("L3 翻译缓存写入失败，本次不缓存（译文仍返回）：{}->{} hash={} reason={}",
                    sourceLang, targetLang, hash, e.getMessage());
        }

        // 写回 L2 / L1（内部各自容错，失败不影响返回值）
        writeBack(key, translated);

        log.info("Translation done {}->{} hash={} len={}", sourceLang, targetLang, hash,
                translated.length());
        return new TranslationOutcome(translated, false);
    }

    /**
     * 写回 L2（Redis）与 L1（本地）。
     */
    private void writeBack(String key, String translated) {
        localCache.put(key, translated);
        writeToRedis(key, translated);
    }

    // package-private：TranslationL2RedisIT 直接驱动 L2 读写路径（真 Redis），私有则只能靠源码形状断言。
    String readFromRedis(String key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis read failed, skip L2: {}", e.getMessage());
            return null;
        }
    }

    void writeToRedis(String key, String value) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, REDIS_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis write failed, skip L2: {}", e.getMessage());
        }
    }

    /**
     * 调用 DeepSeek chat/completions 接口完成翻译。
     */
    // package-private：单元测试通过覆写本方法注入固定译文，把被测点收敛到「写回失败」这条路径，
    // 避免为了测一个缓存写失败去起真实 HTTP。与 readFromRedis / writeToRedis 同一可见性约定。
    String callDeepSeek(String sourceText, String sourceLang, String targetLang)
            throws IOException, InterruptedException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", modelName);

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
        requestBody.addProperty("temperature", temperature);
        requestBody.addProperty("max_tokens", 500);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("DeepSeek API returned status " + response.statusCode()
                    + " body=" + response.body());
        }

        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        String content = extractContent(body);
        if (content == null || content.isBlank()) {
            // 空译文不得写入三级缓存：抛异常走调用方“降级返回原文”分支
            throw new IOException("DeepSeek 返回异常结构或空译文");
        }
        return content;
    }

    /**
     * 从 chat/completions 响应中提取首条 content，结构异常时返回 null（不抛 NPE 穿透）。
     */
    private static String extractContent(JsonObject jsonResponse) {
        try {
            if (jsonResponse == null || !jsonResponse.has("choices")) {
                return null;
            }
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return null;
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                return null;
            }
            return message.get("content").getAsString();
        } catch (Exception e) {
            return null;
        }
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
