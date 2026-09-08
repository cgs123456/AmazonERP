package com.amz.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("KnowledgeSearchService 编排测试")
class KnowledgeSearchServiceTest {

    @Test
    @DisplayName("topN 非法值收敛到 1~10")
    void testNormalizeTopN() {
        assertEquals(1, KnowledgeSearchService.normalizeTopN(0));
        assertEquals(10, KnowledgeSearchService.normalizeTopN(100));
        assertEquals(5, KnowledgeSearchService.normalizeTopN(5));
    }

    @Test
    @DisplayName("空 query 不触达 ES")
    void testBlankQueryShortCircuits() throws Exception {
        HybridSearchService hybrid = mock(HybridSearchService.class);
        RerankService rerank = mock(RerankService.class);
        KnowledgeSearchService service = service(hybrid, rerank);

        assertTrue(service.search(1L, "  ", 5).isEmpty());
        verifyNoInteractions(hybrid, rerank);
    }

    @Test
    @DisplayName("召回量为 topN*3 并透传重排结果")
    void testRecallAndRerank() throws Exception {
        HybridSearchService hybrid = mock(HybridSearchService.class);
        RerankService rerank = mock(RerankService.class);
        KnowledgeSearchService service = service(hybrid, rerank);
        List<KnowledgeChunk> hits = List.of(KnowledgeChunk.builder().content("c").build());
        List<KnowledgeChunk> ranked = List.of(KnowledgeChunk.builder().content("r").build());
        when(hybrid.search(1L, "退货", 15)).thenReturn(hits);
        when(rerank.rerank("退货", hits, 5)).thenReturn(ranked);

        assertSame(ranked, service.search(1L, "退货", 5));
        verify(hybrid).search(1L, "退货", 15);
    }

    private static KnowledgeSearchService service(HybridSearchService hybrid, RerankService rerank) {
        KnowledgeSearchService service = new KnowledgeSearchService();
        ReflectionTestUtils.setField(service, "hybridSearchService", hybrid);
        ReflectionTestUtils.setField(service, "rerankService", rerank);
        return service;
    }
}
