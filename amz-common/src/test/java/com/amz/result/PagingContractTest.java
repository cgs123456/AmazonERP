package com.amz.result;

import com.amz.exception.InvalidParamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一分页契约（P1-01）的守卫测试。
 *
 * <p>这里锁死的都是「一旦退化就会静默漏单」的行为：
 * 探测行泄漏、截断不标记、非法游标静默回退首页、size 越界静默收敛。
 * 任何一条被改回去，本类必须变红。
 */
class PagingContractTest {

    private static final List<Long> DESC_IDS = Arrays.asList(30L, 29L, 28L, 27L);

    private static PageResult<Long> pageOf(List<Long> probed, int size) {
        return PageResult.of(probed, size, PageRequest::encodeCursor);
    }

    @Test
    @DisplayName("命中 size+1 行：截断、给出下一页游标、探测行不进入结果")
    void truncatedWhenProbeRowPresent() {
        PageResult<Long> page = pageOf(DESC_IDS, 3);

        assertEquals(3, page.items().size(), "第 size+1 行只用于判定 hasMore");
        assertTrue(page.truncated());
        assertTrue(page.hasMore());
        assertEquals(PageRequest.encodeCursor(28L), page.nextCursor());
        assertEquals(Arrays.asList(30L, 29L, 28L), page.items());
    }

    @Test
    @DisplayName("刚好命中 size 行：不截断、无游标")
    void notTruncatedWhenExactlyFull() {
        PageResult<Long> page = pageOf(DESC_IDS.subList(0, 3), 3);

        assertEquals(3, page.items().size());
        assertFalse(page.truncated());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("不足一页：不截断、无游标")
    void notTruncatedWhenPartial() {
        PageResult<Long> page = pageOf(Arrays.asList(30L), 50);

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
    }

    @Test
    @DisplayName("空结果：不截断，总数为未知（不是 0）")
    void emptyIsNotTruncatedAndTotalUnknown() {
        PageResult<Long> page = PageResult.empty(20);

        assertTrue(page.items().isEmpty());
        assertFalse(page.truncated());
        assertNull(page.total(), "未做 COUNT(*) 时总数必须是未知，把 null 当 0 会算错页数");
    }

    @Test
    @DisplayName("返回行不可被调用方改写")
    void itemsAreImmutable() {
        PageResult<Long> page = pageOf(DESC_IDS, 3);

        assertThrows(UnsupportedOperationException.class, () -> page.items().add(1L));
    }

    @Test
    @DisplayName("size 非法时构造即报错")
    void rejectsIllegalSize() {
        assertThrows(InvalidParamException.class, () -> pageOf(new ArrayList<>(), 0));
    }

    // ---------------- PageRequest ----------------

    @Test
    @DisplayName("缺省 size 用默认值，空白 cursor 视为首页")
    void defaultsAndBlankCursor() {
        PageRequest req = PageRequest.of(null, "   ");

        assertEquals(PageRequest.DEFAULT_SIZE, req.size());
        assertFalse(req.hasCursor());
        assertNull(req.cursorId());
    }

    @Test
    @DisplayName("size 越界必须报错，而不是静默收敛到上限")
    void rejectsOutOfRangeSize() {
        assertThrows(InvalidParamException.class, () -> PageRequest.of(PageRequest.MAX_SIZE + 1, null));
        assertThrows(InvalidParamException.class, () -> PageRequest.of(0, null));
        assertThrows(InvalidParamException.class, () -> PageRequest.of(-1, null));
    }

    @Test
    @DisplayName("probeSize = size + 1")
    void probeSizeIsOneMore() {
        assertEquals(51, PageRequest.first(50).probeSize());
    }

    @Test
    @DisplayName("游标可往返编解码")
    void cursorRoundTrip() {
        String cursor = PageRequest.encodeCursor(4242L);

        PageRequest req = PageRequest.of(10, cursor);

        assertTrue(req.hasCursor());
        assertEquals(4242L, req.cursorId());
        assertEquals(cursor, req.cursor());
    }

    @Test
    @DisplayName("被篡改或版本不匹配的游标必须报错，绝不静默回退首页")
    void rejectsBadCursor() {
        assertThrows(InvalidParamException.class, () -> PageRequest.of(10, "!!!not-base64!!!"));
        assertThrows(InvalidParamException.class, () -> PageRequest.of(10, "v0:123"));
        assertThrows(InvalidParamException.class, () -> PageRequest.of(10, PageRequest.encodeCursor(0L)));
    }

    @Test
    @DisplayName("游标值缺失时报错，而不是 NPE 变成 500")
    void rejectsMissingCursorValue() {
        assertThrows(InvalidParamException.class, () -> PageRequest.encodeCursor((Long) null));
        assertThrows(InvalidParamException.class, () -> PageRequest.encodeCursor((String) null));
    }

    @Test
    @DisplayName("复合游标：按 (业务时间, id) 排序的列表能原样回传载荷")
    void compositeCursorRoundTrip() {
        String payload = "2026-09-26T10:00:00|8848";
        String cursor = PageRequest.encodeCursor(payload);

        PageRequest req = PageRequest.of(20, cursor);

        assertTrue(req.hasCursor());
        assertEquals(payload, req.payload());
        // 复合游标不是单列 id，取 cursorId 必须报错，而不是解析出半个数字
        assertThrows(InvalidParamException.class, req::cursorId);
    }

    @Test
    @DisplayName("单列游标：payload 就是 id，cursorId 可回读")
    void singleColumnCursor() {
        PageRequest req = PageRequest.of(20, PageRequest.encodeCursor(4242L));

        assertEquals("4242", req.payload());
        assertEquals(4242L, req.cursorId());
    }
    // ---------------- 响应体 ----------------

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("截断响应：data 仍是数组，分页事实在 _page，message 不再是通用成功")
    void pagedJsonCarriesTruncation() throws Exception {
        Result<List<Long>> result = Result.paged(pageOf(DESC_IDS, 3));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));
        assertEquals(3, json.path("data").size(), "老客户端直接读 data 的行为必须保持不变");
        JsonNode page = json.path("_page");
        assertTrue(page.path("truncated").asBoolean());
        assertTrue(page.path("hasMore").asBoolean());
        assertEquals(3, page.path("returned").asInt());
        assertEquals(PageRequest.encodeCursor(28L), page.path("nextCursor").asText());
        assertEquals(Result.MSG_TRUNCATED, json.path("message").asText());
        assertEquals(200, json.path("code").asInt());
    }

    @Test
    @DisplayName("非截断响应：message 保持「操作成功」，_page 仍输出以便客户端统一处理")
    void pagedJsonWithoutTruncation() throws Exception {
        Result<List<Long>> result = Result.paged(pageOf(Arrays.asList(30L), 50));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));
        assertEquals("操作成功", json.path("message").asText());
        assertFalse(json.path("_page").path("truncated").asBoolean());
        assertFalse(json.path("_page").path("hasMore").asBoolean());
    }

    @Test
    @DisplayName("非分页响应不输出 _page，避免污染既有契约")
    void nonPagedResultHasNoPageField() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(Result.success("ok")));

        assertTrue(json.path("_page").isMissingNode());
    }
}