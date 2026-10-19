package com.amz.service;

import com.amz.model.pojo.ProductDoc;

import java.util.List;

/**
 * 商品搜索索引的写入契约。
 * <p>
 * <b>存在动因</b>：{@code amz_product} 索引此前<b>全仓零写入方</b>——
 * {@code SearchServiceImpl} 只读它，而 {@code ProductMasterServiceImpl} 写完 MySQL
 * 之后没有任何通知。结果是索引永远为空，搜索页的空态文案「Elasticsearch 返回的 0 条，
 * 不是没查」在技术上成立、在业务上误导：真实原因是从来没有写入过。
 * 这与 {@code amz_sales_history} 的形状完全一致（实现完整 ≠ 已接通）。
 * <p>
 * <b>为什么放在 search 模块</b>：索引结构（{@link ProductDoc} 的字段类型、分词器、
 * 向量维度）是 search 域的资产，不是 product 域的。让 product 直接依赖
 * {@code spring-data-elasticsearch} 并自己拼 ProductDoc，会导致两处各自演进——
 * 加减一个字段就要同步改两个模块，且类型不一致时只在查询时才暴露。
 * product 只负责「通知有变更」，索引怎么建由 search 决定。
 * <p>
 * <b>为什么不走 MQ</b>：写索引需要「谁改的、改了什么」的即时语义，且失败的可见性
 * 比吞吐量重要得多。MQ 会引入投递延迟与死信处理，而当前量级（商品主数据的新建/更新
 * 是低频运营动作）完全不需要。若将来批量导入量大到不可接受，再换成 MQ 也不影响本接口。
 */
public interface ProductIndexService {

    /**
     * 单条 upsert 索引。
     * <p>
     * 幂等：以 {@code ProductDoc.id}（= {@code amz_product.id}）为主键，重复调用结果相同。
     * 因此重试安全，调用方不必去重。
     *
     * @param doc 待写入的文档；{@code id} 必须非空
     * @return 是否写入成功。false 表示 ES 不可用或写入失败——调用方<b>不得</b>
     *         把它当作成功记入业务日志，但也不应因此回滚 MySQL 事务
     */
    boolean upsert(ProductDoc doc);

    /**
     * 批量 upsert（全量重建索引时使用）。
     *
     * @param docs 文档列表；空列表直接返回 true
     * @return 成功写入的条数
     */
    int upsertBatch(List<ProductDoc> docs);

    /**
     * 删除索引中的一条文档。
     *
     * @param id {@code amz_product.id}
     * @return 是否成功（不存在也算成功——幂等）
     */
    boolean delete(Long id);

    /**
     * 当前索引中的文档数。仅用于重建后核对与运维观测。
     *
     * @return 文档数；ES 不可用时返回 -1（不是 0——「查不到」与「真的空」必须可区分）
     */
    long count();
}
