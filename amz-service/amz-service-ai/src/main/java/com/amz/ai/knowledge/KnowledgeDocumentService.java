package com.amz.ai.knowledge;

import com.amz.service.EmbeddingService;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识文档入库编排：解析 → 分块 → 向量化 → ES 落库。
 * <p>
 * 降级语义（与检索侧对齐）：
 * <ul>
 *   <li>embedding 不可用 → 块无向量落库，仅 BM25 可召回；</li>
 *   <li>向量维度与配置不一致 → 该块跳过向量（warn），不阻断整篇。</li>
 * </ul>
 */
@Slf4j
@Service
public class KnowledgeDocumentService {

    /** 单文件上限 10MB（与头像上传口径一致）。 */
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    @Autowired
    private DocumentParser parser;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    @Autowired
    private KnowledgeEsClient esClient;

    @Autowired
    private KnowledgeIndexService indexService;

    /**
     * 上传入库，返回 {docId, filename, chunks, indexed}。
     */
    public Map<String, Object> uploadDocument(byte[] bytes, String filename, Long shopId) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文档内容为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("文档超过 10MB 上限");
        }
        if (shopId == null) {
            throw new IllegalArgumentException("shopId 不能为空");
        }
        String name = (filename == null || filename.isBlank()) ? "unnamed" : filename;
        String text = parser.parse(bytes, name);
        List<String> chunks = DocumentChunker.chunk(text);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档未解析出有效文本: " + name);
        }

        indexService.ensureIndex();
        String index = indexService.indexName();
        int dims = indexService.vectorDims();
        boolean embeddingOn = embeddingService != null && embeddingService.isAvailable();
        if (!embeddingOn) {
            log.warn("Embedding 不可用，本次入库 {} 块仅 BM25 可召回 docId 待生成", chunks.size());
        }

        String docId = UUID.randomUUID().toString().replace("-", "");
        int indexed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = null;
            if (embeddingOn) {
                try {
                    float[] v = embeddingService.embed(chunks.get(i));
                    if (v != null && v.length == dims) {
                        vector = v;
                    } else if (v != null) {
                        log.warn("向量维度不符（期望 {} 实际 {}），该块跳过向量 docId={}",
                                dims, v.length, docId);
                    }
                } catch (Exception e) {
                    log.warn("单块向量化失败，降级无向量落库 docId={} chunk={}", docId, i, e);
                }
            }
            esClient.indexChunk(index, docId + "#" + i, shopId, docId, name, i, chunks.get(i), vector);
            indexed++;
        }
        log.info("知识文档入库完成 docId={} filename={} chunks={}", docId, name, indexed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("filename", name);
        result.put("chunks", chunks.size());
        result.put("indexed", indexed);
        return result;
    }

    /**
     * 删除整篇文档（先校验归属）。
     *
     * @return 删除块数
     */
    public int deleteDocument(String index, String docId, Long shopId) throws Exception {
        JsonObject one = esClient.findOneByDocId(index, docId);
        if (one == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        long owner = one.has("shop_id") && !one.get("shop_id").isJsonNull()
                ? one.get("shop_id").getAsLong() : -1L;
        if (shopId == null || owner != shopId.longValue()) {
            throw new IllegalStateException("无权操作该店铺文档");
        }
        return esClient.deleteByDocId(index, docId);
    }
}
