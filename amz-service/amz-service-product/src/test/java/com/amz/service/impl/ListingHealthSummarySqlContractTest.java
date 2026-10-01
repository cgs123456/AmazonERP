package com.amz.service.impl;

import com.amz.mapper.ListingHealthMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code healthSummary} 下沉 SQL 后的两条静态契约。
 * <p>
 * 为什么值得钉住（实测 MySQL 8.4.11，按本表 DDL 的 {@code COLLATE=utf8mb4_unicode_ci} 建表）：
 * 同一份 7 行 fixture 里，{@code severity = 'OK'} 数到 <b>3</b> 行（'OK'/'ok'/'Ok' 全算），
 * {@code severity COLLATE utf8mb4_bin = 'OK'} 数到 <b>1</b> 行，
 * 而原实现 Java 侧 {@code "OK".equals(severity)} 也是 1 行。
 * 少了 COLLATE，报表上的 {@code healthRate} 会从 1/7 悄悄变成 3/7 —— 数字仍在、
 * 单元测试仍绿（mock 不经过数据库），只有真机数据会变。
 * <p>
 * 编译期和 mock 都覆盖不到 SQL 文本本身，故用源码文本契约 + 真库 IT
 * （{@code ListingHealthSummaryMySqlIT}）两头夹。
 */
@DisplayName("Listing 健康度聚合 SQL：大小写敏感、列名对齐、不带单读上限")
class ListingHealthSummarySqlContractTest {

    private static final Path MAPPER = Path.of(
            "amz-service/amz-service-product/src/main/java/com/amz/mapper/ListingHealthMapper.java");
    private static final Path SERVICE = Path.of(
            "amz-service/amz-service-product/src/main/java/com/amz/service/impl/ListingMonitorServiceImpl.java");

    private static final List<String> SEVERITIES = List.of("OK", "WARNING", "CRITICAL");

    @Test
    @DisplayName("三个严重度计数都必须按大小写敏感比较（否则 ok 会把 'ok'/'Ok' 也算进去）")
    void severityCountsAreCaseSensitive() {
        String sql = aggregateSql();
        long caseInsensitiveEquals = SEVERITIES.stream()
                .filter(s -> !sql.contains("severity COLLATE utf8mb4_bin = '" + s + "'"))
                .count();
        assertEquals(0, caseInsensitiveEquals,
                "有严重度分支退化成了 collation 敏感的等值比较，实际 SQL：" + sql);
        // 反向确认这些分支真的存在（不存在也会"通过"，所以先数一遍）
        int caseSensitiveBranches = 0;
        for (String severity : SEVERITIES) {
            if (sql.contains("severity COLLATE utf8mb4_bin = '" + severity + "'")) {
                caseSensitiveBranches++;
            }
        }
        assertEquals(SEVERITIES.size(), caseSensitiveBranches,
                "三个严重度分支数量不对，实际 SQL：" + sql);
    }

    @Test
    @DisplayName("聚合回传的列名与服务读取的键一一对应（改名必须两头一起改）")
    void aggregateAliasesMatchWhatServiceReads() {
        String sql = aggregateSql();
        String service = read(SERVICE);
        List<String> keys = List.of("total", "okCount", "warningCount", "criticalCount",
                "scoreSum", "scoreCount");
        for (String key : keys) {
            assertTrue(service.contains("longColumn(row, \"" + key + "\")")
                            || service.contains("decimalColumn(row, \"" + key + "\")"),
                    "服务侧没有在读 " + key + " 这个键（改成别的读法也要同步本契约）");
            assertTrue(sql.contains(" AS " + key + ",") || sql.contains(" AS " + key + " "),
                    "聚合 SQL 里没有 " + key + " 这一列，服务读到的是缺省值：" + sql);
        }
    }

    @Test
    @DisplayName("聚合必须绑店铺、不得带单读上限（口径是整店，不是抽样）")
    void aggregateIsShopScopedAndNeverCapped() {
        String sql = aggregateSql();
        assertTrue(sql.contains("WHERE shop_id = #{shopId}"),
                "聚合必须绑定 shop_id，否则跨店健康度会被合计进来：" + sql);
        assertFalse(sql.toUpperCase().contains("LIMIT"),
                "汇总带 LIMIT 就变成抽样口径，healthRate/avgScore 都会跟着变：" + sql);
    }

    @Test
    @DisplayName("最差清单交给 SQL 排序截断，Java 不再整店读入")
    void worstListIsOrderedAndBoundedInSql() {
        String source = read(MAPPER);
        int anchor = source.indexOf("selectWorstListings");
        assertTrue(anchor >= 0, "找不到 selectWorstListings 方法");
        int from = source.lastIndexOf("@Select", anchor);
        assertTrue(from >= 0, "selectWorstListings 上没有 @Select");
        String sql = joinStringLiterals(source.substring(from, anchor));
        assertTrue(sql.contains("ORDER BY health_score ASC, id ASC"),
                "同分行必须有确定次序，否则同一份数据两次请求给出不同清单：" + sql);
        assertTrue(sql.contains("LIMIT #{limit}"),
                "条数要由调用方传入并落到 SQL 上：" + sql);
        assertTrue(sql.contains("WHERE shop_id = #{shopId}"), "同样要绑店铺：" + sql);
        // 返回 List<Map> 时 MyBatis 用列标签当键（map-underscore-to-camel-case 只管 POJO），
        // 所以别名必须就是服务读的键 —— 这一条是真库 IT 抓出来的 bug，这里做离线兜底。
        for (String key : List.of("asin", "healthScore", "severity", "suppressedReason")) {
            assertTrue(sql.contains("AS " + key),
                    "列 " + key + " 没有驼峰别名，服务侧会读到 null：" + sql);
        }
        String service = read(SERVICE);
        for (String key : List.of("healthScore", "suppressedReason", "severity", "asin")) {
            assertTrue(service.contains("\"" + key + "\""),
                    "服务侧没有按别名读 " + key + "，返回体里该字段会是 null");
        }
    }

    /** 取 aggregateHealthSummary 对应的 @Select 文本（拼接后的字面量）。 */
    private static String aggregateSql() {
        String source = read(MAPPER);
        int anchor = source.indexOf("aggregateHealthSummary");
        assertTrue(anchor >= 0, "找不到 aggregateHealthSummary 方法");
        int from = source.lastIndexOf("@Select", anchor);
        assertTrue(from >= 0, "aggregateHealthSummary 上没有 @Select");
        String block = source.substring(from, source.indexOf("Map<String, Object> aggregateHealthSummary", from));
        String sql = joinStringLiterals(block);
        assertTrue(sql.contains("FROM amz_listing_health"), "看起来不是一段完整查询：" + sql);
        return sql;
    }

    /** 把 {@code @Select("a" + "b")} 的多个片段拼成一条 SQL 文本，并剥掉 {@code <script>} 标签。 */
    private static String joinStringLiterals(String annotationBlock) {
        Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(annotationBlock);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            sb.append(matcher.group(1).replace("\\\"", "\"").replace("&gt;", ">").replace("&lt;", "<"));
        }
        String sql = sb.toString().replaceAll("</?(script|if)[^>]*>", " ");
        return sql.trim();
    }

    private static String read(Path relative) {
        Path root = repoRoot().resolve(relative);
        try {
            return Files.readString(root, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到 " + root, e);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
