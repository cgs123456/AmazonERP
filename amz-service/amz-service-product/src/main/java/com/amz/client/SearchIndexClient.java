package com.amz.client;

import com.amz.client.fallback.SearchIndexClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 商品搜索索引变更通知（product → search）。
 * <p>
 * <b>存在动因</b>：{@code amz_product} 索引此前<b>零写入方</b>——商品主数据写进 MySQL 后
 * 没有通知任何地方，ES 索引永远为空，而搜索页的空态文案说「Elasticsearch 返回的 0 条」。
 * 本客户端补上这条接线。
 * <p>
 * <b>为什么用 Map 而不是共享 DTO</b>：search 侧的 {@code ProductDoc} 属于它的域模型
 * （含 ES 注解），product 不应该依赖 {@code spring-data-elasticsearch}。
 * 这里只发「事实」：id / title / description / sku / shopId / price。
 * 键名以 search 侧 {@code /search/index/notify/**} 的契约为准。
 * <p>
 * <b>降级</b>：{@link SearchIndexClientFallbackFactory} 返回失败。调用方
 * <b>不得</b>因此回滚 MySQL 事务——索引是派生数据；但必须记 WARN，
 * 否则「索引为什么没更新」会再次变成一个排查不出来的问题。
 */
@FeignClient(name = "amz-service-search", contextId = "productSearchIndexClient",
        path = "/internal/search-index",
        fallbackFactory = SearchIndexClientFallbackFactory.class)
public interface SearchIndexClient {

    /**
     * 单条 upsert。
     *
     * @param doc 索引文档字段（键名：id/title/content/sku/shopId/price）
     */
    @PostMapping("/notify/upsert")
    Result<Boolean> upsert(@RequestBody Map<String, Object> doc);

    /**
     * 批量 upsert（CSV 导入后一次性通知）。
     */
    @PostMapping("/notify/upsert-batch")
    Result<Integer> upsertBatch(@RequestBody List<Map<String, Object>> docs);

    /**
     * 删除一条索引文档。
     */
    @DeleteMapping("/notify/{id}")
    Result<Boolean> delete(@PathVariable("id") Long id);
}
