package com.amz.client;

import com.amz.client.fallback.ProductMasterClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 商品主数据读取客户端（search → product）。
 * <p>
 * <b>为什么 search 需要读 product 库</b>：索引重建要从事实源（{@code amz_product} 表）
 * 全量灌一次。search 模块自己的库是 {@code amz_search}（只有搜索历史），
 * 读不到商品表；跨库直连会破坏服务边界，因此走内部接口。
 * <p>
 * <b>为什么只读不写</b>：写入方向是 product → search（变更通知），
 * 反向写会让两个模块互相依赖。本接口刻意只有读方法。
 * <p>
 * <b>降级</b>：product 不可用时返回失败，重建服务据此**不删旧索引**——
 * 「读不到源数据」不能变成「把已有索引清空」。
 */
@FeignClient(name = "amz-service-product", contextId = "searchProductMasterClient",
        path = "/internal/product-master",
        fallbackFactory = ProductMasterClientFallbackFactory.class)
public interface ProductMasterClient {

    /**
     * 分页读取全部商品主数据（按 id 升序 keyset 游标）。
     *
     * @param afterId 上一页最后一条的 id；null/0 表示从头开始
     * @param limit   单页条数（服务端有上限）
     * @return 本页记录；{@code nextCursor} 为 null 表示已到末页
     */
    @GetMapping("/batch")
    Result<BatchPage> fetchBatch(@RequestParam(value = "afterId", required = false) Long afterId,
                                 @RequestParam(value = "limit", defaultValue = "500") Integer limit);

    /**
     * 一页商品主数据。
     * <p>
     * 用 {@code List<Map<String,Object>>} 而不是共享 DTO：search 不需要依赖 product 的
     * 实体类（那会把 MyBatis 注解、@TableName 一起带进来）。字段名以 product 侧为准，
     * 映射到 {@code ProductDoc} 时逐个显式取值，缺字段就是 null，不会静默错位。
     */
    class BatchPage {
        private List<Map<String, Object>> rows;
        private Long nextCursor;

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public void setRows(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        public Long getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(Long nextCursor) {
            this.nextCursor = nextCursor;
        }
    }
}
