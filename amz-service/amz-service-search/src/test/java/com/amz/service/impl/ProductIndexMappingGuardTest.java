package com.amz.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amz.model.pojo.ProductDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.IndexedObjectInformation;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已存在索引的映射核对：不符时必须 ERROR 点名差异，且**绝不**删索引。
 * <p>
 * <b>为什么需要这条测试</b>：最初 {@code ensureIndex()} 在索引已存在时完全不看映射，
 * 注释里的理由是「写入失败会以 ERROR 暴露」。真机验证（HANDOFF §S33.125）证明这只对一半：
 * ES 确实会拒绝（字段类型不能改、dense_vector 维度不能超），但错误只出现在 ES 的响应里，
 * 而 {@code upsertBatch} 的日志只有一句「索引写入失败（ES 不可用或映射冲突）」——
 * 运维看不出哪个字段、期望什么类型。于是补了 {@code verifyExistingMapping()}。
 * <p>
 * <b>本测试用变异验证过</b>：把 {@code if (!required.getValue().equals(actualType))}
 * 改成 {@code if (false)}（即永远认为映射正确），下面第一条断言立刻变红。
 * 这是必要的——我第一版验证脚本只断言了「没有删索引」，变异后仍然全绿，
 * 说明那条断言根本没有覆盖到日志。
 * <p>
 * <b>为什么不连真 ES</b>：本机 JVM 无法建立任何 selector（AF_UNIX loopback 被拒，
 * 见 HANDOFF §S33.125 §8），JDK HttpClient / HttpClient5 / HttpClient4 全部不可用。
 * 因此这里用记录调用的动态代理，真 ES 的行为由 curl 侧验证并写入 HANDOFF。
 */
@DisplayName("索引映射核对：不符时点名差异，且不删索引")
class ProductIndexMappingGuardTest {

    private static final String INDEX = "amz_product";

    private ProductIndexServiceImpl service;
    private FakeOps ops;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        service = new ProductIndexServiceImpl();
        ops = new FakeOps();
        ReflectionTestUtils.setField(service, "elasticsearchOperations", ops.operations());

        logger = (Logger) LoggerFactory.getLogger(ProductIndexServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("字段类型不符：ERROR 点名每个不符字段，且不 create / 不 delete")
    void mismatchedMappingIsReportedByFieldName() {
        ops.indexExists = true;
        // 模拟 8.x 时代遗留：price 是 keyword、embedding 维度 768、title 用已不可用的分词器
        ops.mapping.put("properties", new LinkedHashMap<>(Map.of(
                "title", Map.of("type", "text", "analyzer", "ik_max_word"),
                "content", Map.of("type", "text"),
                "summary", Map.of("type", "text"),
                "price", Map.of("type", "keyword"),
                "sku", Map.of("type", "keyword"),
                "shopId", Map.of("type", "long"),
                "userId", Map.of("type", "integer"),
                "embedding", Map.of("type", "dense_vector", "dims", 768))));

        service.upsertBatch(List.of(doc(1L)));

        List<String> errors = errorMessages();
        assertTrue(errors.stream().anyMatch(m -> m.contains("price") && m.contains("double")
                        && m.contains("keyword")),
                "必须点名 price 期望 double 实际 keyword，实际日志：" + errors);
        assertFalse(errors.stream().anyMatch(m -> m.contains("title")),
                "analyzer 不同不算类型不符（title 仍是 text），不应误报：" + errors);

        assertTrue(ops.calls.contains("getMapping"), "已存在索引必须核对映射：" + ops.calls);
        assertFalse(ops.calls.contains("create"), "不得重建索引：" + ops.calls);
        assertFalse(ops.calls.contains("delete"), "不得删除索引（不可逆）：" + ops.calls);
        // 仍然尝试写入：核对只负责报告，不阻断（ES 自己会拒绝真正不兼容的文档）
        assertTrue(ops.calls.contains("bulkIndex(1)"), "核对后仍应尝试写入：" + ops.calls);
    }

    @Test
    @DisplayName("字段完全缺失：也点名（而不是只在部分不符时报）")
    void missingFieldIsReported() {
        ops.indexExists = true;
        ops.mapping.put("properties", new LinkedHashMap<>(Map.of(
                "title", Map.of("type", "text"),
                "content", Map.of("type", "text"),
                "summary", Map.of("type", "text"),
                "price", Map.of("type", "double"),
                "sku", Map.of("type", "keyword"),
                "shopId", Map.of("type", "long"))));

        service.upsertBatch(List.of(doc(1L)));

        List<String> errors = errorMessages();
        assertTrue(errors.stream().anyMatch(m -> m.contains("embedding") && m.contains("缺失")),
                "embedding 缺失必须点名，实际日志：" + errors);
        assertTrue(errors.stream().anyMatch(m -> m.contains("userId") && m.contains("缺失")),
                "userId 缺失必须点名，实际日志：" + errors);
    }

    @Test
    @DisplayName("映射完全正确：不产生 ERROR（避免每次启动都刷一条假警报）")
    void correctMappingProducesNoError() {
        ops.indexExists = true;
        ops.mapping.put("properties", new LinkedHashMap<>(Map.of(
                "title", Map.of("type", "text"),
                "content", Map.of("type", "text"),
                "summary", Map.of("type", "text"),
                "price", Map.of("type", "double"),
                "sku", Map.of("type", "keyword"),
                "shopId", Map.of("type", "long"),
                "userId", Map.of("type", "integer"),
                "embedding", Map.of("type", "dense_vector", "dims", 1024))));

        service.upsertBatch(List.of(doc(1L)));

        assertEquals(List.of(), errorMessages(), "映射正确时不应有 ERROR");
        assertEquals(List.of("getMapping", "bulkIndex(1)", "refresh"), ops.calls,
                "映射正确时的调用序列：核对一次、写入、refresh");
    }

    @Test
    @DisplayName("读取映射失败：只 WARN，不阻断写入、不删索引")
    void getMappingFailureDoesNotBlockWrites() {
        ops.indexExists = true;
        ops.failGetMapping = true;

        int written = service.upsertBatch(List.of(doc(1L)));

        assertEquals(1, written, "读不到映射不应影响写入");
        assertTrue(appender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("读取商品索引映射失败")),
                "读映射失败要 WARN，实际日志：" + appender.list);
        assertFalse(ops.calls.contains("delete"), "不得删除索引：" + ops.calls);
    }

    // ==================== 辅助 ====================

    private List<String> errorMessages() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel() == Level.ERROR) {
                messages.add(event.getFormattedMessage());
            }
        }
        return messages;
    }

    private static ProductDoc doc(Long id) {
        ProductDoc doc = new ProductDoc();
        doc.setId(id);
        doc.setTitle("无线蓝牙耳机");
        return doc;
    }

    /**
     * 记录调用序列的假 ElasticsearchOperations。
     * <p>
     * 只实现被测代码真正会走的四个方法；其余方法直接抛异常，避免「多调用了一个没预期的方法
     * 却静默通过」。
     */
    private static final class FakeOps {
        final List<String> calls = new ArrayList<>();
        final Map<String, Object> mapping = new LinkedHashMap<>();
        boolean indexExists;
        boolean failGetMapping;

        ElasticsearchOperations operations() {
            return (ElasticsearchOperations) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ElasticsearchOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "indexOps" -> indexOps();
                        case "bulkIndex" -> {
                            calls.add("bulkIndex(" + ((List<?>) args[0]).size() + ")");
                            yield List.<IndexedObjectInformation>of();
                        }
                        case "toString" -> "FakeOps";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private IndexOperations indexOps() {
            return (IndexOperations) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{IndexOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "exists" -> indexExists;
                        case "create" -> {
                            calls.add("create");
                            indexExists = true;
                            yield true;
                        }
                        case "putMapping" -> {
                            calls.add("putMapping");
                            yield true;
                        }
                        case "getMapping" -> {
                            calls.add("getMapping");
                            if (failGetMapping) {
                                throw new IllegalStateException("connection refused");
                            }
                            yield new LinkedHashMap<>(mapping);
                        }
                        case "refresh" -> {
                            calls.add("refresh");
                            yield null;
                        }
                        case "toString" -> "FakeIndexOps";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** 未使用的类型引用，保留以说明导入来源（IndexCoordinates 等由被测代码内部构造）。 */
    @SuppressWarnings("unused")
    private static final Class<?>[] REFERENCED_TYPES = {
            IndexCoordinates.class, IndexQuery.class, Document.class};
}
