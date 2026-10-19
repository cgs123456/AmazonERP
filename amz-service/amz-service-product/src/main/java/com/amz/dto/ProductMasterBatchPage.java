package com.amz.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 商品主数据的 keyset 分页结果（供 search 侧全量重建索引）。
 * <p>
 * <b>为什么用 {@code List<Map<String,Object>>} 而不是 {@code List<AmzProduct>}</b>：
 * 消费方是 {@code amz-service-search}，它不依赖本模块的 MyBatis 实体。
 * 用 Map 传输时字段名就是契约本身，search 侧逐个显式取值、缺字段即 null，
 * 不会因为两个模块的实体类版本不一致而静默错位（对比：共享 DTO 会在字段改名时
 * 编译期通过、运行期丢字段）。
 * <p>
 * 代价是字段名变更不会有编译错误提示。为此在本类的 {@link #FIELD_NAMES} 里
 * 显式列出对外承诺的字段，改动时两边都要看这份清单。
 */
@Data
public class ProductMasterBatchPage {

    /**
     * 对外承诺的字段名。search 侧的映射逻辑读取的就是这些键。
     * 新增字段是兼容的；<b>改名或删除必须同步 search 侧的 {@code ProductIndexRebuildService}</b>。
     */
    public static final List<String> FIELD_NAMES = List.of(
            "id", "shopId", "sku", "asin", "marketplaceId", "title", "description",
            "brand", "price", "currency", "category", "productType", "sizeTier",
            "weightG", "status");

    /** 本页记录。 */
    private List<Map<String, Object>> rows;

    /**
     * 下一页的游标（本页最后一条的 id）。
     * <p>
     * {@code null} 表示已到末页——调用方必须据此停止，不能假设「返回条数 < limit」
     * 就是末页（product 侧可能因过滤条件少返回，但后面还有数据）。
     */
    private Long nextCursor;
}
