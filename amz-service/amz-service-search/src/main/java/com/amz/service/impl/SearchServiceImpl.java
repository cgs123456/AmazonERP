package com.amz.service.impl;

import com.amz.client.UserClient;
import com.amz.constant.RedisConstant;
import com.amz.context.UserContext;
import com.amz.mapper.HistoryMapper;
import com.amz.model.pojo.History;
import com.amz.model.pojo.Note;
import com.amz.model.vo.NoteVo;
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

@Service
@Slf4j
public class SearchServiceImpl implements SearchService {

    /** BM25 检索候选数 */
    private static final int BM25_TOP = 50;
    /** kNN 检索候选数 */
    private static final int KNN_TOP = 50;
    /** kNN 候选池大小（num_candidates，ES Java Client 要求 Long 类型） */
    private static final long KNN_CANDIDATES = 100L;
    /** RRF 融合后返回数 */
    private static final int RRF_FINAL = 20;
    /** RRF 平滑常数 */
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
    public Result<List<NoteVo>> search(String key) {
        // 1.混合检索 / 降级纯 BM25
        List<Note> notes = searchNotes(key);

        // 2.构造 VO
        List<NoteVo> noteVos = new ArrayList<>();
        for (Note note : notes) {
            NoteVo noteVo = new NoteVo();
            BeanUtils.copyProperties(note, noteVo);
            noteVo.setUser(userClient.getUserById(note.getUserId()).getData());
            noteVos.add(noteVo);
        }

        // 3.保存搜索记录（去重）
        try {
            Integer currentUserId = UserContext.getUserId();
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<History> queryWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            queryWrapper.eq(History::getUserId, currentUserId)
                       .eq(History::getHistory, key);
            History existingHistory = historyMapper.selectOne(queryWrapper);

            if (existingHistory != null) {
                log.info("搜索记录已存在，更新: userId={}, key={}", currentUserId, key);
            } else {
                History history = new History();
                history.setHistory(key);
                history.setUserId(currentUserId);
                historyMapper.insert(history);
            }
        } catch (Exception e) {
            log.info("用户搜索记录处理异常: {}", e.getMessage());
        }

        // 4.保存热度
        redisTemplate.opsForZSet().incrementScore(RedisConstant.NOTE_SCORE, key, 1);
        return Result.success(noteVos);
    }

    /**
     * 笔记检索入口：
     * - Embedding 服务可用 → BM25 Top50 + kNN Top50 → RRF 融合 → Top20
     * - 不可用 → 降级纯 BM25 Top20
     */
    private List<Note> searchNotes(String key) {
        if (embeddingService.isAvailable()) {
            try {
                return hybridSearch(key);
            } catch (Exception e) {
                log.warn("混合检索异常，降级为纯 BM25: {}", e.getMessage());
            }
        }
        return bm25Search(key, RRF_FINAL);
    }

    /** BM25 + kNN 混合检索 + RRF 融合 */
    private List<Note> hybridSearch(String key) {
        // 1.生成查询向量
        float[] queryEmbedding = embeddingService.embed(key);
        if (queryEmbedding == null) {
            log.warn("查询向量化失败，降级为纯 BM25");
            return bm25Search(key, RRF_FINAL);
        }

        // 2.BM25 文本检索 Top50（title + content + AI 摘要 summary）
        Query bm25Query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(key)
                        .fields("title", "content", "summary")
                ))
                .withMaxResults(BM25_TOP)
                .build();
        SearchHits<Note> bm25Hits = elasticsearchOperations.search(
                bm25Query, Note.class, IndexCoordinates.of("amz_note"));

        // 3.kNN 向量检索 Top50
        List<Float> vector = toFloatList(queryEmbedding);
        Query knnQuery = NativeQuery.builder()
                .withQuery(q -> q.knn(k -> k
                        .field("embedding")
                        .queryVector(vector)
                        .numCandidates(KNN_CANDIDATES)
                ))
                .withMaxResults(KNN_TOP)
                .build();
        SearchHits<Note> knnHits = elasticsearchOperations.search(
                knnQuery, Note.class, IndexCoordinates.of("amz_note"));

        // 4.RRF 融合：score = sum(1 / (k + rank))
        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, Note> noteMap = new HashMap<>();

        int rank = 1;
        for (SearchHit<Note> hit : bm25Hits.getSearchHits()) {
            Long id = hit.getContent().getId();
            rrfScores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
            noteMap.put(id, hit.getContent());
            rank++;
        }

        rank = 1;
        for (SearchHit<Note> hit : knnHits.getSearchHits()) {
            Long id = hit.getContent().getId();
            rrfScores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
            noteMap.putIfAbsent(id, hit.getContent());
            rank++;
        }

        // 5.按 RRF 分数降序取 Top20
        List<Note> result = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(RRF_FINAL)
                .map(e -> noteMap.get(e.getKey()))
                .collect(Collectors.toList());

        log.info("混合检索 key={} BM25命中={} kNN命中={} RRF融合后={}",
                key, bm25Hits.getTotalHits(), knnHits.getTotalHits(), result.size());
        return result;
    }

    /** 纯 BM25 文本检索（含 AI 摘要 summary 字段增强） */
    private List<Note> bm25Search(String key, int size) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(key)
                        .fields("title", "content", "summary")
                ))
                .withMaxResults(size)
                .build();
        SearchHits<Note> hits = elasticsearchOperations.search(
                query, Note.class, IndexCoordinates.of("amz_note"));
        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }

    /** float[] → List<Float>（ES Java Client kNN 查询要求） */
    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) {
            list.add(f);
        }
        return list;
    }
}
