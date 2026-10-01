package com.amz.service.impl;

import com.amz.exception.InvalidParamException;
import com.amz.mapper.ConversationMemoryMapper;
import com.amz.model.ConversationMemory;
import com.amz.result.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话记忆查询的 limit 边界。
 * <p>
 * <b>实测发现的缺陷</b>：{@code GET /agent/memory/history/{userId}?limit=}
 * 把调用方给的整数直接拼进 {@code LIMIT}，此前只有 {@code Math.max(1, limit)} 这一层下限保护，
 * 没有上限 —— {@code ?limit=1000000000} 就是让该会话的全部历史行进入内存并整体序列化返回。
 * 同仓库里 {@code PageRequest} 的既定口径是"越界即参数错误，不静默收敛"，这里沿用同一口径。
 */
@DisplayName("对话记忆 limit 必须落在 1..PageRequest.MAX_SIZE")
class MemoryHistoryLimitTest {

    private final ConversationMemoryMapper mapper = mock(ConversationMemoryMapper.class);
    private final MemoryServiceImpl service = service();

    private MemoryServiceImpl service() {
        MemoryServiceImpl impl = new MemoryServiceImpl();
        ReflectionTestUtils.setField(impl, "conversationMemoryMapper", mapper);
        return impl;
    }

    @Test
    @DisplayName("超限/非法值直接拒绝，且信息里给出允许区间")
    void rejectsOutOfRange() {
        for (int bad : new int[]{0, -5, PageRequest.MAX_SIZE + 1, Integer.MAX_VALUE}) {
            InvalidParamException ex = assertThrows(InvalidParamException.class,
                    () -> service.listRecentMemories("sess-1", bad),
                    "limit=" + bad + " 应当被拒绝");
            assertTrue(ex.getMessage().contains("1.." + PageRequest.MAX_SIZE), ex.getMessage());
        }
        verify(mapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    @DisplayName("区间内的值照常查询，结果按时间正序返回")
    void acceptsInScopeValues() {
        ConversationMemory older = memory(1L);
        ConversationMemory newer = memory(2L);
        // mapper 按 id 倒序返回，服务层负责翻正
        when(mapper.selectList(any())).thenReturn(List.of(newer, older));

        List<ConversationMemory> result =
                assertDoesNotThrow(() -> service.listRecentMemories("sess-1", PageRequest.MAX_SIZE));

        assertEquals(1L, result.get(0).getId(), "oldest first：便于直接拼进 messages");
        assertEquals(2L, result.get(1).getId());
    }

    private static ConversationMemory memory(Long id) {
        ConversationMemory m = new ConversationMemory();
        m.setId(id);
        m.setSessionId("sess-1");
        return m;
    }
}
