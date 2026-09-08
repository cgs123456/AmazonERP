package com.amz.ai.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 知识库索引管理：建库 mapping 与向量维度校验。
 * <p>
 * 向量维度必须与 embedding 模型输出一致（OpenAI 系默认 1536），
 * 通过 {@code knowledge.es.vector-dims} 配置；维度 mismatch 的块在入库时跳过向量。
 */
@Slf4j
@Service
public class KnowledgeIndexService {

    @Autowired
    private KnowledgeEsClient esClient;

    @Value("${knowledge.es.index:amz_knowledge_base}")
    private String index;

    @Value("${knowledge.es.vector-dims:1536}")
    private int vectorDims;

    public String indexName() {
        return index;
    }

    public int vectorDims() {
        return vectorDims;
    }

    /**
     * 索引不存在则创建（mapping 见 KnowledgeEsClient.buildMapping）。
     */
    public void ensureIndex() throws Exception {
        esClient.ensureIndex(index, vectorDims);
    }
}
