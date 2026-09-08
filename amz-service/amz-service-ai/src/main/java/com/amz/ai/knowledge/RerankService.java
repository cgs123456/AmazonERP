package com.amz.ai.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重排服务（当前为词法重排）。
 * <p>
 * 策略说明：ONNX BGE-reranker 需要约 1GB 模型文件 + BERT 分词器，
 * 在本环境无法验证其行为；为避免带病上线占位代码，当前以确定性词法
 * 评分（查询词在块中的加权命中）实现同一接口。后续接入 ONNX 时，
 * 只需新增一个同接口实现类并将其标为 {@code @Primary} 即可无缝替换，
 * 调用方（工具/控制器）零改动。
 */
@Slf4j
@Service
public class RerankService {

    /**
     * 对检索结果重排并截断。空输入直接返回空列表。
     */
    public List<KnowledgeChunk> rerank(String query, List<KnowledgeChunk> chunks, int topN) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Set<String> terms = tokenize(query);
        List<Scored> scored = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            double s = terms.isEmpty() ? 0 : lexicalScore(terms, chunk == null ? null : chunk.getContent());
            scored.add(new Scored(chunk, s));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.max(1, topN);
        List<KnowledgeChunk> result = new ArrayList<>(Math.min(n, scored.size()));
        for (int i = 0; i < scored.size() && result.size() < n; i++) {
            if (scored.get(i).chunk != null) {
                scored.get(i).chunk.setScore(scored.get(i).score);
                result.add(scored.get(i).chunk);
            }
        }
        return result;
    }

    /**
     * 查询分词：按非字母数字切分，小写化，丢弃单字符噪声。
     */
    static Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null) {
            return terms;
        }
        for (String token : text.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() > 1) {
                terms.add(token);
            }
        }
        return terms;
    }

    /**
     * 词法分：命中词数加权（出现 3 次封顶，避免长块刷分）。
     */
    static double lexicalScore(Set<String> terms, String content) {
        if (terms.isEmpty() || content == null || content.isEmpty()) {
            return 0;
        }
        String lower = content.toLowerCase();
        double score = 0;
        for (String term : terms) {
            int count = 0;
            int from = 0;
            while (count < 3) {
                int at = lower.indexOf(term, from);
                if (at < 0) {
                    break;
                }
                count++;
                from = at + term.length();
            }
            score += count;
        }
        return score;
    }

    private static final class Scored {
        final KnowledgeChunk chunk;
        final double score;

        Scored(KnowledgeChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
