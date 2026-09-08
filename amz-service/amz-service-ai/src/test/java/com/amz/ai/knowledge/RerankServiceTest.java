package com.amz.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RerankService 词法重排测试")
class RerankServiceTest {

    private final RerankService rerankService = new RerankService();

    @Test
    @DisplayName("分词小写化并丢弃单字符")
    void testTokenize() {
        Set<String> terms = RerankService.tokenize("Hello, 世界 x");
        assertTrue(terms.contains("hello"));
        assertTrue(terms.contains("世界"));
        assertFalse(terms.contains("x"));
    }

    @Test
    @DisplayName("同一词出现 3 次封顶")
    void testLexicalScoreCapped() {
        double score = RerankService.lexicalScore(
                Set.of("退货", "退款"), "退货 退货 退货 退货 退款");
        assertEquals(4.0, score);
    }

    @Test
    @DisplayName("重排按命中排序并截断")
    void testRerankOrdersAndTruncates() {
        KnowledgeChunk hit = KnowledgeChunk.builder()
                .docId("d").filename("退货政策.pdf").chunkIndex(0).content("退货政策说明").build();
        KnowledgeChunk miss = KnowledgeChunk.builder()
                .docId("d").filename("物流.pdf").chunkIndex(1).content("完全无关的内容").build();
        List<KnowledgeChunk> ranked = rerankService.rerank("退货", List.of(miss, hit), 1);
        assertEquals(1, ranked.size());
        assertEquals("退货政策.pdf", ranked.get(0).getFilename());
        assertTrue(ranked.get(0).getScore() > 0);
    }
}
