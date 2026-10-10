package com.amz.service.impl;

import com.amz.constant.RedisConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.Cursor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FieldPermissionServiceImpl} 缓存对账契约。
 * <p>
 * 存在动因（2026-10-10 实测）：原实现只往 Redis 里 {@code SADD}，从不删除成员或 key。
 * 权限规则一旦在库里被撤销（{@code visible} 1→0 改回 1，或整行删除），Redis 里的旧成员
 * 会永久残留；而 {@code getHiddenFields} 优先读 Redis 且非空即返回，于是**撤销权限永远不生效**。
 * 这组测试把「DB 是唯一事实源、Redis 只是可重建缓存」钉死。
 */
@DisplayName("FieldPermissionServiceImpl 缓存对账测试")
class FieldPermissionServiceImplTest {

    private FieldPermissionServiceImpl service;
    private JdbcTemplate jdbcTemplate;
    private RedisTemplate<String, Object> redisTemplate;
    private SetOperations<String, Object> setOps;

    /** 模拟 Redis Set：key -> members，供 members/add/remove 三个动作共享同一份状态。 */
    private final Map<String, Set<Object>> redisSets = new HashMap<>();
    /** 模拟 Redis keyspace 里的 key 集合（含空 Set 的 key）。 */
    private final Set<String> redisKeys = new HashSet<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new FieldPermissionServiceImpl();
        jdbcTemplate = mock(JdbcTemplate.class);
        redisTemplate = mock(RedisTemplate.class);
        setOps = mock(SetOperations.class);

        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        ObjectProvider<RedisTemplate<String, Object>> redisProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        ReflectionTestUtils.setField(service, "jdbcTemplateProvider", jdbcProvider);
        ReflectionTestUtils.setField(service, "redisTemplateProvider", redisProvider);

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(inv -> cursorOf(new ArrayList<>(redisKeys)));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            redisKeys.remove(key);
            redisSets.remove(key);
            return Boolean.TRUE;
        });
        when(setOps.members(anyString())).thenAnswer(inv -> new HashSet<>(
                redisSets.getOrDefault(inv.getArgument(0), Collections.emptySet())));
        when(setOps.add(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object value = inv.getArgument(1);
            redisKeys.add(key);
            redisSets.computeIfAbsent(key, k -> new HashSet<>()).add(value);
            return 1L;
        });
        when(setOps.remove(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object value = inv.getArgument(1);
            Set<Object> members = redisSets.get(key);
            return members != null && members.remove(value) ? 1L : 0L;
        });
    }

    private static Cursor<String> cursorOf(List<String> keys) {
        return new Cursor<String>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < keys.size();
            }

            @Override
            public String next() {
                return keys.get(index++);
            }

            @Override
            public long getCursorId() {
                return index;
            }

            @Override
            public org.springframework.data.redis.core.Cursor.CursorId getId() {
                return org.springframework.data.redis.core.Cursor.CursorId.of((long) index);
            }

            @Override
            public long getPosition() {
                return index;
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public void close() {
            }
        };
    }

    private static Map<String, Object> rule(String role, String entity, String field) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role_code", role);
        row.put("entity_name", entity);
        row.put("field_name", field);
        return row;
    }

    @Test
    @DisplayName("库中已撤销的字段必须从 Redis 移除（否则撤销权限永远不生效）")
    void staleMemberIsRemovedOnReload() {
        String key = RedisConstant.FIELD_PERM_PREFIX + "VIEWER:ProfitReport";
        redisKeys.add(key);
        redisSets.put(key, new HashSet<>(Arrays.asList("productCost", "grossProfit")));
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(List.of(rule("VIEWER", "ProfitReport", "productCost")));

        service.loadPermissions();

        verify(setOps).remove(eq(key), eq("grossProfit"));
        assertTrue(!redisSets.get(key).contains("grossProfit"), "grossProfit 已不在 DB，必须从缓存移除");
        assertTrue(redisSets.get(key).contains("productCost"), "productCost 仍在 DB，必须保留");
        verify(setOps, never()).add(eq(key), eq("productCost"));
    }

    @Test
    @DisplayName("DB 里整行删除的规则，其 Redis key 必须一并删除")
    void staleKeyIsDeletedWhenRuleRowIsGone() {
        String key = RedisConstant.FIELD_PERM_PREFIX + "VIEWER:ProfitReport";
        redisKeys.add(key);
        redisSets.put(key, new HashSet<>(Collections.singletonList("productCost")));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());

        service.loadPermissions();

        verify(redisTemplate).delete(key);
        assertTrue(!redisKeys.contains(key), "无 DB 规则支撑的 key 必须删除");
    }

    @Test
    @DisplayName("新增的隐藏规则要写进 Redis 与内存兜底缓存")
    void newRuleIsCached() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(List.of(rule("OPERATOR", "PurchaseOrder", "unitPrice")));

        service.loadPermissions();

        String key = RedisConstant.FIELD_PERM_PREFIX + "OPERATOR:PurchaseOrder";
        verify(setOps).add(eq(key), eq("unitPrice"));
        assertEquals(new HashSet<>(Collections.singletonList("unitPrice")),
                service.getHiddenFields("OPERATOR", "PurchaseOrder"));
    }

    @Test
    @DisplayName("Redis 对账失败不能让规则丢失：内存兜底仍可读到本次加载结果")
    void redisFailureFallsBackToMemoryCache() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(List.of(rule("OPERATOR", "PurchaseOrder", "unitPrice")));
        when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("Redis 不可用"));

        assertDoesNotThrow(() -> service.loadPermissions());

        assertEquals(new HashSet<>(Collections.singletonList("unitPrice")),
                service.getHiddenFields("OPERATOR", "PurchaseOrder"));
    }

    @Test
    @DisplayName("无 JdbcTemplate（amz-common 单独运行）时跳过加载且不抛异常")
    void missingJdbcTemplateIsSkipped() {
        ObjectProvider<JdbcTemplate> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        ReflectionTestUtils.setField(service, "jdbcTemplateProvider", emptyProvider);

        assertDoesNotThrow(() -> service.loadPermissions());

        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }
}