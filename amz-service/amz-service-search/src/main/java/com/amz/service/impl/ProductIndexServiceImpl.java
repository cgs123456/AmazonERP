package com.amz.service.impl;

import com.amz.service.ProductIndexService;
import com.amz.model.pojo.ProductDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品搜索索引写入实现。
 * <p>
 * <b>为什么要自己建映射，而不是 {@code createWithMapping()}</b>：
 * {@link ProductDoc} 上的 {@code @Field(analyzer = "ik_max_word")} 依赖 IK 分词插件，
 * 而本仓 Compose 与 k8s 用的都是<b>官方 {@code elasticsearch:9.2.8} 镜像</b>，
 * 没有任何一处安装过 analysis-ik（全仓 grep 无结果）。直接按注解建映射会在
 * ES 侧报 {@code analyzer [ik_max_word] not found}，索引建不出来、documents 一条也写不进。
 * 因此这里用<b>内置 analyzer</b>（{@code standard} + {@code cjk} 组合）显式构造映射。
 * <p>
 * <b>中文检索的实际效果</b>：{@code cjk} 分词器把连续 CJK 字符切成二元组
 * （"无线耳机" → 无线/线耳/耳机），对中文商品标题的召回已经可用；英文按 standard 处理。
 * 若将来确实引入 IK 插件，改本类的 {@link #buildMapping()} 即可，不影响写入契约。
 * <p>
 * <b>写入失败不抛异常</b>：索引是搜索域的派生数据，MySQL 是事实源。ES 暂时不可用
 * 不应该让商品主数据的新建/更新失败（那会让运营以为保存不了）。但失败必须
 * 返回 false 并打 ERROR——静默吞掉会让「索引为空」再次变成一个没人发现的问题。
 */
@Slf4j
@Service
public class ProductIndexServiceImpl implements ProductIndexService {

    /** 索引名：与 {@link ProductDoc} 的 {@code @Document} 及 SearchServiceImpl 的查询保持一致。 */
    static final String INDEX_NAME = "amz_product";

    /** 中文/多语言文本字段的分析器（官方 ES 内置，无需插件）。 */
    private static final String TEXT_ANALYZER = "cjk";

    /**
     * 索引映射中必须存在的字段及其类型。
     * <p>
     * <b>为什么要复核</b>：ES 的映射一旦建立就不能修改字段类型。若索引是更早版本
     * （例如按 {@code ProductDoc} 注解自动建的、或某个字段类型不同的旧版本）留下的，
     * {@code exists()} 会返回 true，映射却与当前代码不匹配；此时写入要么被 ES 拒绝，
     * 要么字段被静默丢弃（例如 embedding 维度不对），表现为「保存成功但搜不到」。
     * 这里只在<b>缺失或类型不符</b>时报错——不擅自删索引，那是不可逆操作。
     */
    static final Map<String, String> REQUIRED_MAPPING = Map.of(
            // image 不在其中：它是 index=false 的展示字段，不进检索，类型不符也不影响搜索
            "title", "text",
            "content", "text",
            "summary", "text",
            "price", "double",
            "sku", "keyword",
            "shopId", "long",
            "userId", "integer",
            "embedding", "dense_vector");

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    /**
     * 索引是否已就绪（映射已建）。缓存为 volatile 布尔：索引一旦建好就不会退回去；
     * 但 ES 重启丢索引时 {@code exists()} 会变 false，故在每次写入前廉价复核一次。
     */
    private volatile boolean mappingReady = false;

    @Override
    public boolean upsert(ProductDoc doc) {
        if (doc == null || doc.getId() == null) {
            log.warn("商品索引 upsert 被拒绝：文档或 id 为空（id 是幂等键）");
            return false;
        }
        return upsertBatch(List.of(doc)) > 0;
    }

    @Override
    public int upsertBatch(List<ProductDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        // 逐条校验 id：没有 id 的文档无法幂等 upsert，混进批次会让整批失败
        List<ProductDoc> valid = new ArrayList<>(docs.size());
        for (ProductDoc doc : docs) {
            if (doc != null && doc.getId() != null) {
                valid.add(doc);
            } else {
                log.warn("商品索引批次中跳过无 id 的文档（无法幂等）");
            }
        }
        if (valid.isEmpty()) {
            return 0;
        }
        try {
            ensureIndex();
            IndexCoordinates index = IndexCoordinates.of(INDEX_NAME);
            List<IndexQuery> queries = new ArrayList<>(valid.size());
            for (ProductDoc doc : valid) {
                queries.add(new IndexQueryBuilder()
                        .withId(String.valueOf(doc.getId()))
                        .withObject(doc)
                        .build());
            }
            elasticsearchOperations.bulkIndex(queries, index);
            // 显式 refresh：搜索是紧接写入的下一步操作（运营改完主数据就去搜），
            // 不 refresh 会读到旧数据，表现为「保存成功了但搜不到」。
            IndexOperations indexOps = elasticsearchOperations.indexOps(index);
            indexOps.refresh();
            log.info("商品索引写入 {} 条（index={}）", valid.size(), INDEX_NAME);
            return valid.size();
        } catch (Exception e) {
            // 不抛：索引是派生数据，ES 故障不应阻断 MySQL 事实源的写入。
            // 但必须 ERROR 级别——这条日志是「索引为什么是空的」的唯一线索。
            log.error("商品索引写入失败（ES 不可用或映射冲突），index={} 条数={}",
                    INDEX_NAME, valid.size(), e);
            mappingReady = false;
            return 0;
        }
    }

    @Override
    public boolean delete(Long id) {
        if (id == null) {
            return false;
        }
        try {
            elasticsearchOperations.delete(String.valueOf(id), IndexCoordinates.of(INDEX_NAME));
            return true;
        } catch (Exception e) {
            log.error("商品索引删除失败 id={}", id, e);
            return false;
        }
    }

    @Override
    public long count() {
        try {
            // -1 而不是 0：search 页的空态文案要能区分「索引真没有」与「ES 查不到」。
            // count 查询本身在 ES 不可用时会抛，不会返回 0。
            return elasticsearchOperations.count(
                    org.springframework.data.elasticsearch.client.elc.NativeQuery.builder()
                            .withQuery(q -> q.matchAll(m -> m))
                            .build(),
                    IndexCoordinates.of(INDEX_NAME));
        } catch (Exception e) {
            log.warn("商品索引计数失败（ES 不可用）：{}", e.getMessage());
            return -1L;
        }
    }

    /**
     * 确保索引存在且映射正确。
     * <p>
     * 只在索引不存在时创建；已存在则不动（避免每次写入都覆盖映射，
     * 也避免与运维手工调整的 mapping 打架）。
     */
    private synchronized void ensureIndex() {
        if (mappingReady) {
            return;
        }
        IndexOperations indexOps = elasticsearchOperations.indexOps(IndexCoordinates.of(INDEX_NAME));
        boolean created = false;
        if (!indexOps.exists()) {
            created = indexOps.create(buildSettings());
            if (created) {
                indexOps.putMapping(buildMapping());
            }
        } else {
            // 索引已存在：不动映射，但必须核对。类型不符时 ES 会拒绝写入或静默丢字段，
            // 与其让运营看到「保存成功却搜不到」，不如在第一次写入就 ERROR 点名差异。
            verifyExistingMapping(indexOps);
            created = true;
        }
        mappingReady = created;
    }

    /**
     * 核对已存在索引的字段类型。
     * <p>
     * 不抛异常、不删索引：由 {@link #upsertBatch} 的 catch 统一记为写入失败，
     * 日志里给出「哪个字段该是什么类型、实际是什么」，运维据此决定是否重建索引。
     */
    private void verifyExistingMapping(IndexOperations indexOps) {
        Map<String, Object> mapping;
        try {
            mapping = indexOps.getMapping();
        } catch (Exception e) {
            log.warn("读取商品索引映射失败（index={}）：{}", INDEX_NAME, e.getMessage());
            return;
        }
        Map<String, Object> properties = propertiesOf(mapping);
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> required : REQUIRED_MAPPING.entrySet()) {
            Object fieldDef = properties.get(required.getKey());
            String actualType = fieldDef instanceof Map<?, ?> def && def.get("type") != null
                    ? String.valueOf(def.get("type")) : null;
            if (!required.getValue().equals(actualType)) {
                mismatches.add(required.getKey() + " 期望 " + required.getValue()
                        + " 实际 " + (actualType == null ? "缺失" : actualType));
            }
        }
        if (!mismatches.isEmpty()) {
            log.error("商品索引映射与当前代码不符（index={}）：{}。"
                    + "写入可能被 ES 拒绝或字段被静默丢弃；需删除并重建索引"
                    + "（见 HANDOFF 的索引重建步骤），本服务不会自动删索引。",
                    INDEX_NAME, String.join("；", mismatches));
        }
    }

    /** getMapping 返回的结构是 { properties: {...} }（也可能被包一层索引名），两种都兼容。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Map<String, Object> mapping) {
        Object properties = mapping == null ? null : mapping.get("properties");
        if (properties instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Object nested = mapping == null ? null : mapping.get(INDEX_NAME);
        if (nested instanceof Map<?, ?> indexDef) {
            Object inner = indexDef.get("properties");
            if (inner instanceof Map<?, ?> innerMap) {
                return (Map<String, Object>) innerMap;
            }
        }
        return Map.of();
    }

    /** 索引设置：中文用内置 cjk 分词（无需 IK 插件）。 */
    private Map<String, Object> buildSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        return settings;
    }

    /**
     * 索引映射：与 {@link ProductDoc} 的字段一一对应，但 analyzer 用内置值。
     * <p>
     * {@code embedding} 声明为 {@code dense_vector}，维度取 {@link ProductDoc} 的 1024；
     * 只有 embedding 服务启用时才写值，未启用时该字段为空——kNN 查询在没有向量时
     * 由 SearchServiceImpl 的 isAvailable() 分支挡住，不会执行。
     */
    private Document buildMapping() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("title", textField());
        properties.put("content", textField());
        properties.put("summary", textField());

        properties.put("image", Map.of("type", "keyword", "index", false));
        properties.put("price", Map.of("type", "double"));
        properties.put("sku", Map.of("type", "keyword"));
        properties.put("shopId", Map.of("type", "long"));
        properties.put("userId", Map.of("type", "integer"));

        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("type", "dense_vector");
        embedding.put("dims", 1024);
        embedding.put("index", true);
        embedding.put("similarity", "cosine");
        properties.put("embedding", embedding);

        return Document.from(Map.of("properties", properties));
    }

    private Map<String, Object> textField() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "text");
        field.put("analyzer", TEXT_ANALYZER);
        field.put("search_analyzer", TEXT_ANALYZER);
        return field;
    }
}
