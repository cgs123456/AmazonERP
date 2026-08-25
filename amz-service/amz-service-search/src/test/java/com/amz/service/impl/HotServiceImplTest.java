package com.amz.service.impl;

import com.amz.constant.RedisConstant;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 热搜服务单元测试（纯 Mockito，不依赖 Redis 容器）。
 * <p>
 * 原 {@code AmzServiceSearchApplicationTests} 依赖 Redis 容器连接，CI 中被 {@code @Disabled} 跳过。
 * 现改为 mock {@link RedisTemplate} 的 ZSet 操作，验证 {@link HotServiceImpl} 的
 * 热搜聚合逻辑。实现使用 {@code reverseRangeWithScores} 一次取回成员与分数
 * （替代旧的 ZREVRANGE + N 次 ZSCORE），mock 口径与之对齐。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("热搜服务单元测试")
class HotServiceImplTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @InjectMocks
    private HotServiceImpl hotService;

    /** 构造 TypedTuple（默认 DefaultTuple 需要 byte 序列化器，此处用匿名实现）。 */
    private TypedTuple<Object> tuple(Object value, Double score) {
        return new TypedTuple<Object>() {
            @Override
            public int compareTo(TypedTuple<Object> o) {
                return Double.compare(score, o == null ? null : o.getScore());
            }

            @Override
            public Object getValue() {
                return value;
            }

            @Override
            public Double getScore() {
                return score;
            }
        };
    }

    /**
     * Redis 中存在热搜词时，应返回按分数排序的热搜列表。
     */
    @Test
    @DisplayName("有热搜数据 → 返回前10名及对应分数")
    void testGetHotListWithResults() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        Set<TypedTuple<Object>> tuples = new LinkedHashSet<>(List.of(
                tuple("蓝牙耳机", 100.0),
                tuple("手机壳", 80.5)));
        when(zSetOperations.reverseRangeWithScores(eq(RedisConstant.PRODUCT_SCORE), eq(0L), eq(9L)))
                .thenReturn(tuples);

        Result<List<Map<String, Object>>> result = hotService.getHotList();

        assertEquals(200, result.getCode(), "状态码应为 200");
        assertNotNull(result.getData(), "热搜列表不应为 null");
        assertEquals(2, result.getData().size(), "应返回 2 条热搜");

        Map<String, Object> first = result.getData().get(0);
        assertEquals("蓝牙耳机", first.get("key"), "第一条热搜 key 应为蓝牙耳机");
        assertEquals(100.0, first.get("score"), "第一条热搜 score 应为 100.0");

        Map<String, Object> second = result.getData().get(1);
        assertEquals("手机壳", second.get("key"), "第二条热搜 key 应为手机壳");
        assertEquals(80.5, second.get("score"), "第二条热搜 score 应为 80.5");
    }

    /**
     * Redis 中无热搜数据时，应返回 data=null（不抛异常）。
     */
    @Test
    @DisplayName("无热搜数据 → 返回 null data")
    void testGetHotListEmpty() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(eq(RedisConstant.PRODUCT_SCORE), eq(0L), eq(9L)))
                .thenReturn(Collections.emptySet());

        Result<List<Map<String, Object>>> result = hotService.getHotList();

        assertEquals(200, result.getCode(), "空热搜时状态码仍应为 200");
        assertNull(result.getData(), "空热搜应返回 null data");
    }

    /**
     * Redis 返回 null 时，应返回 data=null（不抛 NPE）。
     */
    @Test
    @DisplayName("Redis 返回 null → 返回 null data 不抛异常")
    void testGetHotListNullFromRedis() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores(eq(RedisConstant.PRODUCT_SCORE), eq(0L), eq(9L)))
                .thenReturn(null);

        Result<List<Map<String, Object>>> result = hotService.getHotList();

        assertEquals(200, result.getCode());
        assertNull(result.getData(), "Redis 返回 null 时 data 应为 null");
    }
}
