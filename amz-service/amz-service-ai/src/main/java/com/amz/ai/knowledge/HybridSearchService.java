package com.amz.ai.knowledge;

import com.amz.context.UserContext;
import com.amz.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：BM25 + kNN → 应用层 RRF 融合（与 search 服务同公式、同 k 值语义）。
 * <p>
 * 店铺隔离：显式 shopId 优先；缺省时取当前用户授权店铺列表；
 * 内部调用（无授权上下文）才放行全量——与 {@code UserContext.isShopAllowed} 的
 * 信任模型一致。
 */
@Slf4j
@Service
public class HybridSearchService {

    @Autowired
    private KnowledgeEsClient esClient;

    @Autowired
    private KnowledgeIndexService indexService;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    @Value("${knowledge.search.bm25-top:50}")
    private int bm25Top;

    @Value("${knowledge.search.knn-top:50}")
    private int knnTop;

    @Value("${knowledge.search.rrf-k:60.0}")
    private double rrfK;

    /**
     * 混合检索，返回按融合分排序的块（最多 topK）。
     */
    public List<KnowledgeChunk> search(Long shopId, String query, int topK) throws Exception {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Long> scopes = resolveScopes(shopId);
        String index = indexService.indexName();

        List<KnowledgeEsClient.ScoredChunk> bm25Hits =
                esClient.searchBm25(index, scopes, query, bm25Top);

        float[] vector = null;
        if (embeddingService != null && embeddingService.isAvailable()) {
            try {
                vector = embeddingService.embed(query);
            } catch (Exception e) {
                log.warn("查询向量化失败，降级纯 BM25", e);
            }
        } else {
            log.debug("Embedding 不可用，本次走纯 BM25");
        }
        List<KnowledgeEsClient.ScoredChunk> knnHits = List.of();
        if (vector != null && vector.length > 0) {
            knnHits = esClient.searchKnn(index, scopes, vector, knnTop);
        }

        List<String> bm25Ids = new ArrayList<>(bm25Hits.size());
        Map<String, KnowledgeChunk> byId = new LinkedHashMap<>();
        for (KnowledgeEsClient.ScoredChunk h : bm25Hits) {
            bm25Ids.add(h.getId());
            byId.putIfAbsent(h.getId(), h.getChunk());
        }
        List<String> knnIds = new ArrayList<>(knnHits.size());
        for (KnowledgeEsClient.ScoredChunk h : knnHits) {
            knnIds.add(h.getId());
            byId.putIfAbsent(h.getId(), h.getChunk());
        }

        List<String> ranked = fuseRankings(bm25Ids, knnIds, rrfK);
        List<KnowledgeChunk> result = new ArrayList<>();
        int n = Math.max(1, topK);
        for (int i = 0; i < ranked.size() && result.size() < n; i++) {
            KnowledgeChunk chunk = byId.get(ranked.get(i));
            if (chunk != null) {
                chunk.setScore((double) (ranked.size() - i));
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 解析生效的店铺过滤范围。
     */
    static List<Long> resolveScopes(Long shopId) {
        if (shopId != null) {
            return List.of(shopId);
        }
        List<Long> authorized = UserContext.getShops();
        if (authorized == null || authorized.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(authorized);
    }

    /**
     * 应用层 RRF 融合（纯函数，可单测）：score = Σ 1/(k + rank)，rank 从 1 起。
     *
     * @return 按融合分降序的 id 列表（同分保持 BM25 优先的稳定顺序）
     */
    static List<String> fuseRankings(List<String> bm25Ids, List<String> knnIds, double k) {
        Map<String, Double> scores = new HashMap<>();
        List<String> order = new ArrayList<>();
        accumulate(scores, order, bm25Ids, k);
        accumulate(scores, order, knnIds, k);
        List<String> ranked = new ArrayList<>(order);
        ranked.sort((a, b) -> {
            int cmp = Double.compare(scores.get(b), scores.get(a));
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(order.indexOf(a), order.indexOf(b));
        });
        return ranked;
    }

    private static void accumulate(Map<String, Double> scores, List<String> order,
                                   List<String> ids, double k) {
        if (ids == null) {
            return;
        }
        int rank = 1;
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            scores.merge(id, 1.0 / (k + rank), Double::sum);
            if (!order.contains(id)) {
                order.add(id);
            }
            rank++;
        }
    }

    /**
     * 按 score 降序（仅供展示层备用；主路径已在 search 内截断）。
     */
    static final Comparator<KnowledgeChunk> BY_SCORE_DESC =
            Comparator.comparing(KnowledgeChunk::getScore,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed();
}
