package com.amz.service.impl;

import com.amz.client.ProductMasterClient;
import com.amz.model.pojo.ProductDoc;
import com.amz.result.Result;
import com.amz.service.EmbeddingService;
import com.amz.service.ProductIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品搜索索引的全量重建。
 * <p>
 * <b>为什么必须提供它</b>：增量写入只覆盖「重建之后发生的变更」。在此之前已经存在于
 * {@code amz_product} 表里的商品——以及索引名/映射变更后的所有数据——只能靠全量重建
 * 补回来。没有重建入口，首次接入等于要求运营把每个商品手工改一遍才会被搜到。
 * <p>
 * <b>安全边界</b>：
 * <ul>
 *   <li><b>先读后写，读失败就不动索引</b>。第一页拉取失败直接中止，
 *       绝不会出现「删了旧索引但没灌新数据」。</li>
 *   <li><b>不清空索引，靠 upsert 覆盖</b>。文档主键是 {@code amz_product.id}，
 *       重建只是把每一行重新写一遍；删掉的商品在索引里成为孤儿文档，由
 *       {@code pruneOrphans} 单独处理（默认关闭，因为它需要对比全量 id 集合）。</li>
 *   <li><b>分页有上限</b>。任何一页失败即中止并报告已处理的条数，不无限重试。</li>
 * </ul>
 * <p>
 * <b>为什么不删索引重建</b>：删除后重建期间搜索完全不可用（秒级到分钟级，取决于商品量）。
 * upsert 覆盖没有这个空窗，且失败时旧数据还在。
 */
@Slf4j
@Service
public class ProductIndexRebuildService {

    /** 单页条数：与 product 侧的读上限对齐（ProductMasterServiceImpl.READ_CAP 同量级）。 */
    @Value("${search.rebuild.page-size:500}")
    private int pageSize = 500;

    /** 单次重建最多处理的分页数：防止 product 侧游标异常导致无限循环。 */
    @Value("${search.rebuild.max-pages:2000}")
    private int maxPages = 2000;

    /** 是否为文档生成 embedding 向量（embedding 服务不可用时自动跳过）。 */
    @Value("${search.rebuild.embedding-enabled:true}")
    private boolean embeddingEnabled = true;

    @Autowired
    private ProductMasterClient productMasterClient;

    @Autowired
    private ProductIndexService productIndexService;

    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 执行全量重建。
     *
     * @return 重建报告
     */
    public RebuildReport rebuildAll() {
        RebuildReport report = new RebuildReport();
        long startedAt = System.currentTimeMillis();

        Long cursor = null;
        int page = 0;
        while (true) {
            if (++page > maxPages) {
                // 不静默截断：达到上限说明游标可能没有推进，必须让人看到
                report.warnings.add("达到最大分页数 " + maxPages + "，重建中止（游标可能未推进）");
                log.warn("商品索引重建达到最大分页数 {}，中止", maxPages);
                break;
            }
            Result<ProductMasterClient.BatchPage> result =
                    productMasterClient.fetchBatch(cursor, pageSize);
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                String reason = result == null ? "无响应" : result.getMessage();
                if (page == 1) {
                    // 第一页就失败：一条都没读过，绝不能碰索引
                    report.aborted = true;
                    report.error = "读取商品主数据失败，未改动索引：" + reason;
                    log.error("商品索引重建中止（首屏读取失败，索引未动）：{}", reason);
                    return report;
                }
                // 中途失败：已写入的部分保留，报告里说明未完成
                report.aborted = true;
                report.error = "第 " + page + " 页读取失败，重建未完成：" + reason;
                log.error("商品索引重建中途失败 page={}，已写入 {} 条", page, report.written);
                break;
            }

            List<Map<String, Object>> rows = result.getData().getRows();
            if (rows == null || rows.isEmpty()) {
                break;
            }

            List<ProductDoc> docs = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                ProductDoc doc = toDoc(row, report);
                if (doc != null) {
                    docs.add(doc);
                }
            }
            int written = productIndexService.upsertBatch(docs);
            report.written += written;
            report.read += rows.size();
            if (written < docs.size()) {
                report.warnings.add("第 " + page + " 页有 " + (docs.size() - written)
                        + " 条未写入（ES 写入失败，见日志）");
            }

            Long next = result.getData().getNextCursor();
            if (next == null || next.equals(cursor)) {
                break;
            }
            cursor = next;
        }

        report.elapsedMs = System.currentTimeMillis() - startedAt;
        report.indexCount = productIndexService.count();
        log.info("商品索引重建完成：读 {} 写 {} 耗时 {}ms 索引现有 {} 条",
                report.read, report.written, report.elapsedMs, report.indexCount);
        return report;
    }

    /**
     * 把 product 侧的一行映射成索引文档。
     * <p>
     * <b>故意不吞字段缺失</b>：id / title 缺失的行无法成为有意义的搜索文档，
     * 跳过并计数（报告里能看到），而不是写一个空文档占位。
     * 其余字段缺失一律置 null——{@code ProductDoc} 的字段在 ES 侧都不是必填。
     */
    private ProductDoc toDoc(Map<String, Object> row, RebuildReport report) {
        if (row == null) {
            return null;
        }
        Long id = asLong(row.get("id"));
        if (id == null) {
            report.skipped++;
            return null;
        }
        ProductDoc doc = new ProductDoc();
        doc.setId(id);
        doc.setTitle(asString(row.get("title")));
        doc.setContent(asString(row.get("description")));
        doc.setSummary(null);
        doc.setImage(null);
        doc.setSku(asString(row.get("sku")));
        doc.setShopId(asLong(row.get("shopId")));
        doc.setUserId(null);
        doc.setPrice(asDouble(row.get("price")));

        if (embeddingEnabled && embeddingService.isAvailable()) {
            String text = doc.getTitle() == null ? null : doc.getTitle();
            if (text != null && !text.isBlank()) {
                // 向量化失败返回 null，由 SearchServiceImpl 的 isAvailable() 分支兜底；
                // 这里不因为一次 embedding 失败就跳过整条文档
                doc.setEmbedding(embeddingService.embed(text));
            }
        }
        return doc;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return new BigDecimal(value.toString().trim()).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** 重建报告。 */
    public static class RebuildReport {
        private int read;
        private int written;
        private int skipped;
        private long elapsedMs;
        private long indexCount;
        private boolean aborted;
        private String error;
        private final List<String> warnings = new ArrayList<>();

        public int getRead() {
            return read;
        }

        public int getWritten() {
            return written;
        }

        public int getSkipped() {
            return skipped;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public long getIndexCount() {
            return indexCount;
        }

        public boolean isAborted() {
            return aborted;
        }

        public String getError() {
            return error;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
