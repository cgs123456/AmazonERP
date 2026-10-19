package com.amz.client.fallback;

import com.amz.client.SearchIndexClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 搜索索引通知降级。
 * <p>
 * 返回失败而不是 success：这样 product 侧能区分「通知成功」与「search 不可用」，
 * 并在日志里留下痕迹。静默返回成功会让索引漏更新，而漏更新的唯一症状是
 * 「改完商品搜不到」——从现象反推原因的成本极高。
 */
@Slf4j
@Component
public class SearchIndexClientFallbackFactory implements FallbackFactory<SearchIndexClient> {

    @Override
    public SearchIndexClient create(Throwable cause) {
        log.warn("Feign call to amz-service-search (index notify) degraded", cause);
        final String reason = "search service degraded: " + cause.getMessage();
        return new SearchIndexClient() {
            @Override
            public Result<Boolean> upsert(Map<String, Object> doc) {
                return Result.failure(reason);
            }

            @Override
            public Result<Integer> upsertBatch(List<Map<String, Object>> docs) {
                return Result.failure(reason);
            }

            @Override
            public Result<Boolean> delete(Long id) {
                return Result.failure(reason);
            }
        };
    }
}
