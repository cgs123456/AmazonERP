package com.amz.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DocumentChunker 分块测试")
class DocumentChunkerTest {

    @Test
    @DisplayName("空输入返回空列表")
    void testEmptyInput() {
        assertTrue(DocumentChunker.chunk(null).isEmpty());
        assertTrue(DocumentChunker.chunk("   \n\t  ").isEmpty());
    }

    @Test
    @DisplayName("短文本归一化后为单块")
    void testShortTextNormalized() {
        List<String> chunks = DocumentChunker.chunk("a  b\n\n\nc");
        assertEquals(List.of("a b\n\nc"), chunks);
    }

    @Test
    @DisplayName("长文本按 800/700 滑动且相邻块重叠 100 字符")
    void testLongTextSlidingWindow() {
        String text = "a".repeat(2000);
        List<String> chunks = DocumentChunker.chunk(text);
        assertEquals(3, chunks.size());
        assertEquals(800, chunks.get(0).length());
        assertEquals(800, chunks.get(1).length());
        assertEquals(600, chunks.get(2).length());
        assertEquals(chunks.get(0).substring(700, 800), chunks.get(1).substring(0, 100));
    }
}
