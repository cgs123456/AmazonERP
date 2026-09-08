package com.amz.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("KnowledgeEsClient DSL 与解析测试（无 live ES）")
class KnowledgeEsClientTest {

    @Test
    @DisplayName("mapping 含可配维度的 cosine dense_vector")
    void testBuildMapping() {
        String mapping = KnowledgeEsClient.buildMapping(1536);
        assertTrue(mapping.contains("\"embedding\":{\"type\":\"dense_vector\",\"dims\":1536"));
        assertTrue(mapping.contains("\"similarity\":\"cosine\""));
    }

    @Test
    @DisplayName("parseHits 按命中顺序还原块")
    void testParseHits() {
        String body = "{\"hits\":{\"hits\":["
                + "{\"_id\":\"d#0\",\"_source\":{\"doc_id\":\"d\",\"filename\":\"f.pdf\","
                + "\"chunk_index\":0,\"content\":\"c0\"}},"
                + "{\"_id\":\"d#1\",\"_source\":{\"doc_id\":\"d\",\"filename\":\"f.pdf\","
                + "\"chunk_index\":1,\"content\":\"c1\"}}]}}";
        List<KnowledgeEsClient.ScoredChunk> hits = KnowledgeEsClient.parseHits(body);
        assertEquals(2, hits.size());
        assertEquals("d#0", hits.get(0).getId());
        assertEquals("c1", hits.get(1).getChunk().getContent());
        assertEquals(1, hits.get(1).getChunk().getChunkIndex());
    }
}
