package com.amz.client;

import com.amz.http.ResilientHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

/**
 * 1688 TokenManager 单元测试（不依赖真实 Redis / 1688 网关）。
 * <p>
 * 通过内存 Map 模拟 Redis 缓存行为，验证：缓存未命中 → 刷新并写回缓存 → 二次命中不再发起 HTTP；
 * 凭证缺失 / 出站通道未注入 → 诚实失败。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("1688 TokenManager 测试")
class Alibaba1688TokenManagerTest {

    @Mock
    private ResilientHttpClient http;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> ops;

    @BeforeEach
    void setUp() {
        // 默认 stub：缓存未命中（get 返回 null）。具体用例可再覆盖为带内存存储的行为。
        when(redisTemplate.opsForValue()).thenReturn(ops);
    }

    @Test
    @DisplayName("缓存未命中触发刷新，刷新结果写回 Redis，二次调用命中缓存不再刷新")
    void testRefreshAndCache() {
        Map<String, String> store = new HashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), anyLong(), any());

        when(http.post(eq("1688"), anyString(), any(), any()))
                .thenReturn("{\"access_token\":\"AT-123\",\"refresh_token\":\"RT-456\",\"expires_in\":3600}");

        Alibaba1688TokenManager mgr = new Alibaba1688TokenManager(http, redisTemplate,
                "ak", "as", "rt", "https://gw.open.1688.com/openapi");

        String token = mgr.getAccessToken();
        assertEquals("AT-123", token);

        String token2 = mgr.getAccessToken();
        assertEquals("AT-123", token2);

        // 仅刷新一次：第二次应命中 Redis 缓存
        verify(http, times(1)).post(eq("1688"), anyString(), any(), any());
    }

    @Test
    @DisplayName("凭证缺失时抛出诚实失败异常（不静默降级）")
    void testMissingCredential() {
        Alibaba1688TokenManager mgr = new Alibaba1688TokenManager(http, redisTemplate,
                "", "as", "rt", "https://gw.open.1688.com/openapi");
        assertThrows(IllegalStateException.class, mgr::getAccessToken);
    }

    @Test
    @DisplayName("出站通道未注入且凭证齐全时抛出明确异常")
    void testHttpNotInjected() {
        Alibaba1688TokenManager mgr = new Alibaba1688TokenManager(null, redisTemplate,
                "ak", "as", "rt", "https://gw.open.1688.com/openapi");
        assertThrows(IllegalStateException.class, mgr::getAccessToken);
    }
}
