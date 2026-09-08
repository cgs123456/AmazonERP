package com.amz.ai.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识检索编排：混合召回 → 词法重排 → 截断。
 * <p>
 * HTTP 控制器与 Agent 工具共用同一入口，保证两端行为一致；
 * topN 钳制在 1~10，召回量为 topN*3（上限 30），避免 ES 拉全量。
 */
@Slf4j
@Service
public class KnowledgeSearchService {

    /** 单次返回上限（与 Agent 工具、HTTP 接口共用）。 */
    public static final int MAX_TOP_N = 10;

    /** 召回放大倍数与上限。 */
    private static final int RECALL_MULTIPLIER = 3;
    private static final int MAX_RECALL = 30;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private RerankService rerankService;

    /**
     * 检索并重排。空 query 直接返回空列表（调用方负责参数文案）。
     */
    public List<KnowledgeChunk> search(Long shopId, String query, int topN) throws Exception {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = normalizeTopN(topN);
        int recall = Math.min(limit * RECALL_MULTIPLIER, MAX_RECALL);
        List<KnowledgeChunk> hits = hybridSearchService.search(shopId, query, recall);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return rerankService.rerank(query, hits, limit);
    }

    /**
     * topN 归一化：非法值收敛到 1~10。
     */
    static int normalizeTopN(int topN) {
        return Math.max(1, Math.min(topN, MAX_TOP_N));
    }
}
