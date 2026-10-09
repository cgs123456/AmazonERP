package com.amz.service;

import com.amz.mapper.TranslationCacheMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.MessageDigest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 翻译 L2 缓存的真 Redis 集成测试（CI 的 redis:7.2 service 上执行）。
 * <p>
 * {@code RedissonConfigTest} 只能断言源码形状（可选注入 + 判空），钉不住"StringRedisTemplate
 * 真的能写进 Redis、TTL 真的生效、L2 命中真的短路 L3/LLM"。Redisson 移除改 StringRedisTemplate
 * 之后（坑 46），这条链路第一次有了行为级守卫。
 * <p>
 * 触发：{@code TRANSLATION_L2_REDIS_IT_HOST}（CI 注入 127.0.0.1:6379）；
 * 不设变量整类跳过，与 {@code *_MySqlIT} 家族同一纪律。
 * 三级缓存路径约定：本 IT 只驱动 L2 与降级分支，不触碰 L3 mapper 之外的 HTTP/DB——
 * L2 未命中时 mapper 必须被调用（证明没有跳过降级），L2 命中时 mapper 必须零调用。
 */
@EnabledIfEnvironmentVariable(named = "TRANSLATION_L2_REDIS_IT_HOST", matches = ".+")
@DisplayName("翻译 L2 缓存：真 Redis 上的读写、TTL 与命中短路")
class TranslationL2RedisIT {

    private static final String KEY_PREFIX = "amz:trans:";
    private static final String HOST = System.getenv("TRANSLATION_L2_REDIS_IT_HOST");
    private static final String PORT = System.getenv().getOrDefault("TRANSLATION_L2_REDIS_IT_PORT", "6379");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private TranslationService service;
    private TranslationCacheMapper mapper;

    @BeforeAll
    static void connectRedis() {
        factory = new LettuceConnectionFactory(HOST, Integer.parseInt(PORT));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void shutdown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void buildService() {
        mapper = Mockito.mock(TranslationCacheMapper.class);
        service = new TranslationService();
        ReflectionTestUtils.setField(service, "redisTemplate", template);
        ReflectionTestUtils.setField(service, "translationCacheMapper", mapper);
        // L1 会在 translate() 命中后写本地缓存；每个用例前清空，避免用例间串扰。
        localCacheClear();
    }

    @AfterEach
    void cleanupKeys() {
        var keys = template.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    @Test
    @DisplayName("writeBack 后 readFromRedis 能读回，且 TTL 已设置（60 分钟档）")
    void writeThenReadRoundTripsWithTtl() {
        String key = KEY_PREFIX + "it-roundtrip:en:de";
        service.writeToRedis(key, "Übersetzung");

        String back = service.readFromRedis(key);
        assertEquals("Übersetzung", back, "L2 读写必须无损（String 编解码，不是 Redisson 的 codec 语义）");
        Long ttl = template.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= Duration.ofMinutes(60).toSeconds() + 5,
                "TTL 必须落在 60 分钟档内，实际：" + ttl);
    }

    @Test
    @DisplayName("translate() 命中 L2 时直接返回缓存值，且不再查 L3/不碰 LLM")
    void translateHitsL2AndSkipsDownstream() throws Exception {
        String sourceText = "L2 hit integration";
        String key = KEY_PREFIX + sha256Hex(sourceText) + ":en:de";
        template.opsForValue().set(key, "L2-Treffer");

        String translated = service.translate(sourceText, "en", "de");

        assertEquals("L2-Treffer", translated, "L2 命中必须短路返回缓存值");
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("L2 未命中时必须落到 L3 查询（Redis 只是加速层，不是唯一事实源）")
    void missFallsThroughToL3() {
        String sourceText = "L2 miss falls through";
        // mapper 桩：返回 null（L3 也未命中）→ 服务继续走 LLM；LLM 配置未注入会抛错并按
        // 「LLM 不可用」降级返回原文——这正是容错链路的一部分，本用例只断言"查过 L3"。
        Mockito.when(mapper.selectOne(any())).thenReturn(null);

        service.translate(sourceText, "en", "de");

        verify(mapper).selectOne(any());
    }

    @Test
    @DisplayName("redisTemplate 缺失时 L2 读写静默跳过，不抛异常（Redis 不可用不阻塞主链路）")
    void nullTemplateSkipsL2Silently() {
        TranslationService bare = new TranslationService();
        ReflectionTestUtils.setField(bare, "translationCacheMapper", Mockito.mock(TranslationCacheMapper.class));

        bare.writeToRedis(KEY_PREFIX + "no-template:en:de", "v");
        assertNull(bare.readFromRedis(KEY_PREFIX + "no-template:en:de"), "无模板时读取必须返回 null");
    }

    private static void localCacheClear() {
        // L1 是 private final 字段，没有清空入口；用例各自用不同 sourceText 即可天然隔离，
        // 这里保留方法壳以显式说明"串扰靠输入隔离，不靠反射掏 private"。
    }

    private static String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}