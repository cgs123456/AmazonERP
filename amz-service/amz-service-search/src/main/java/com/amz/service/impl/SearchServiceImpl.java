package com.amz.service.impl;

import com.amz.client.UserClient;
import com.amz.constant.RedisConstant;
import com.amz.context.UserContext;
import com.amz.mapper.HistoryMapper;
import com.amz.model.pojo.History;
import com.amz.model.pojo.ProductDoc;
import com.amz.model.vo.ProductVo;
import com.amz.result.Result;
import com.amz.service.EmbeddingService;
import com.amz.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品搜索服务实现。
 * <p>
 * 检索策略：
 * <ol>
 *   <li>Embedding 服务可用 → BM25 Top50 + kNN Top50 → RRF 融合 → Top20</li>
 *   <li>不可用 → 降级纯 BM25 Top20</li>
 * </ol>
 * 索引：amz_product（替代原 amz_note）
 */
@Service
@Slf4j
public class SearchServiceImpl implements SearchService {

    private static final int BM25_TOP = 50;
    private static final int KNN_TOP = 50;
    private static final long KNN_CANDIDATES = 100L;
    private static final int RRF_FINAL = 20;
    private static final double RRF_K = 60.0;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private UserClient userClient;

    @Autowired
    private HistoryMapper historyMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private EmbeddingService embeddingService;

    @Override
    public Result<List<ProductVo>> search(String key) {
        List<ProductDoc> products = searchProducts(key);

        // 批量装配用户信息：去重 + 本地短 TTL 缓存，消除逐条串行 Feign 的 N+1 放大
        List<ProductVo> productVos = new ArrayList<>(products.size());
        Map<Integer, Object> userCacheHit = new HashMap<>();
        for (ProductDoc product : products) {
            ProductVo vo = new ProductVo();
            BeanUtils.copyProperties(product, vo);
            Integer uid = product.getUserId();
            if (uid != null) {
                vo.setUser(userCacheHit.computeIfAbsent(uid, this::loadUserCached));
            }
            productVos.add(vo);
        }

        try {
            Integer currentUserId = UserContext.getUserId();
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<History> queryWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            queryWrapper.eq(History::getUserId, currentUserId)
                       .eq(History::getHistory, key);
            History existingHistory = historyMapper.selectOne(queryWrapper);

            if (existingHistory != null) {
                log.debug("搜索记录已存在，更新: userId={}, key={}", currentUserId, key);
            } else {
                History history = new History();
                history.setHistory(key);
                history.setUserId(currentUserId);
                historyMapper.insert(history);
            }
        } catch (Exception e) {
            // 搜索记录为辅助功能，失败不应阻断主流程；但需记录完整堆栈便于排查
            log.warn("用户搜索记录处理异常: userId={}, key={}", UserContext.getUserId(), key, e);
        }

        try {
            redisTemplate.opsForZSet().incrementScore(RedisConstant.PRODUCT_SCORE, key, 1);
            trimHotSearch();
        } catch (Exception e) {
            log.warn("热搜计数失败（不阻断搜索）: {}", e.getMessage());
        }
        return Result.success(productVos);
    }

    /** 热搜 ZSET 最大保留条目数：超出部分从低分端裁剪，防止无限增长。 */
    private static final long HOT_SCORE_MAX_ENTRIES = 1000L;
    /** 热搜 ZSET 过期时间：7 天无写入自动消亡。 */
    private static final java.time.Duration HOT_SCORE_TTL = java.time.Duration.ofDays(7);

    /**
     * 裁剪热搜 ZSET：仅保留分数最高的 TOP N，并滑动续期。
     * 每次搜索多一次 ZREMRANGEBYRANK + EXPIRE，代价远低于集合无限膨胀。
     */
    private void trimHotSearch() {
        try {
            redisTemplate.opsForZSet().removeRange(
                    RedisConstant.PRODUCT_SCORE, 0, -(HOT_SCORE_MAX_ENTRIES + 1));
            redisTemplate.expire(RedisConstant.PRODUCT_SCORE, HOT_SCORE_TTL);
        } catch (Exception e) {
            log.debug("热搜裁剪失败: {}", e.getMessage());
        }
    }

    /** 用户信息本地缓存条目。 */
    private static final long USER_CACHE_TTL_MS = 5 * 60 * 1000L;
    private final java.util.concurrent.ConcurrentHashMap<Integer, UserCacheEntry> userLocalCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class UserCacheEntry {
        final Object user;
        final long expiresAtMs;

        UserCacheEntry(Object user, long expiresAtMs) {
            this.user = user;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /**
     * 带缓存的用户查询：命中直接返回；未命中走 Feign 并回填（含失败负缓存 30s，防穿透打爆下游）。
     */
    private Object loadUserCached(Integer userId) {
        long now = System.currentTimeMillis();
        UserCacheEntry entry = userLocalCache.get(userId);
        if (entry != null && entry.expiresAtMs > now) {
            return entry.user;
        }
        Object user = null;
        try {
            user = userClient.getUserById(userId).getData();
        } catch (Exception e) {
            log.warn("查询用户信息失败 userId={}: {}", userId, e.getMessage());
        }
        long ttl = (user != null) ? USER_CACHE_TTL_MS : 30_000L;
        userLocalCache.put(userId, new UserCacheEntry(user, now + ttl));
        return user;
    }

    private List<ProductDoc> searchProducts(String key) {
        if (embeddingService.isAvailable()) {
            try {
                return hybridSearch(key);
            } catch (Exception e) {
                log.warn("混合检索异常，降级为纯 BM25: {}", e.getMessage());
            }
        }
        return bm25Search(key, RRF_FINAL);
    }

    private List<ProductDoc> hybridSearch(String key) {
        float[] queryEmbedding = embeddingService.embed(key);
        if (queryEmbedding == null) {
            log.warn("查询向量化失败，降级为纯 BM25");
            return bm25Search(key, RRF_FINAL);
        }

        Query bm25Query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(key)
                        .fields("title", "content", "summary")
                ))
                .withMaxResults(BM25_TOP)
                .build();
        SearchHits<ProductDoc> bm25Hits = elasticsearchOperations.search(
                bm25Query, ProductDoc.class, IndexCoordinates.of("amz_product"));

        List<Float> vector = toFloatList(queryEmbedding);
        Query knnQuery = NativeQuery.builder()
                .withQuery(q -> q.knn(k -> k
                        .field("embedding")
                        .queryVector(vector)
                        .numCandidates(KNN_CANDIDATES)
                ))
                .withMaxResults(KNN_TOP)
                .build();
        SearchHits<ProductDoc> knnHits = elasticsearchOperations.search(
                knnQuery, ProductDoc.class, IndexCoordinates.of("amz_product"));

        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, ProductDoc> productMap = new HashMap<>();

        int rank = 1;
        for (SearchHit<ProductDoc> hit : bm25Hits.getSearchHits()) {
            Long id = hit.getContent().getId();
            rrfScores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
            productMap.put(id, hit.getContent());
            rank++;
        }

        rank = 1;
        for (SearchHit<ProductDoc> hit : knnHits.getSearchHits()) {
            Long id = hit.getContent().getId();
            rrfScores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
            productMap.putIfAbsent(id, hit.getContent());
            rank++;
        }

        List<ProductDoc> result = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(RRF_FINAL)
                .map(e -> productMap.get(e.getKey()))
                .collect(Collectors.toList());

        log.debug("混合检索 key={} BM25命中={} kNN命中={} RRF融合后={}",
                key, bm25Hits.getTotalHits(), knnHits.getTotalHits(), result.size());
        return result;
    }

    private List<ProductDoc> bm25Search(String key, int size) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(key)
                        .fields("title", "content", "summary")
                ))
                .withMaxResults(size)
                .build();
        SearchHits<ProductDoc> hits = elasticsearchOperations.search(
                query, ProductDoc.class, IndexCoordinates.of("amz_product"));
        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) {
            list.add(f);
        }
        return list;
    }
}
