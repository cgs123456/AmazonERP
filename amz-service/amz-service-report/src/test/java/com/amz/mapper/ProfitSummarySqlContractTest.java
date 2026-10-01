package com.amz.mapper;

import com.amz.model.ProfitDetail;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 利润 ASIN 聚合 SQL 的静态契约。
 * <p>
 * 与 {@code PaymentCollectionSummarySqlContractTest} 同一动因：SQL 进了注解就脱离编译期检查，
 * 列改名、别名与服务读取键漂移只能在运行时炸。两个测试分属不同模块，
 * 仓库里没有跨模块共享 test-jar 的基建，故各自实现（重复是模块边界的代价，不是疏忽）。
 * <p>
 * 聚合语义本身（COALESCE、日期区间、跨店隔离）是在 MySQL 8.4 上对同一份 fixture
 * 实跑比对确认的，不是靠本文件推断。
 */
@DisplayName("利润 ASIN 聚合 SQL 与实体/服务的静态契约")
class ProfitSummarySqlContractTest {

    private static final Path ROOT = repoRoot();
    private static final Path MAPPER = ROOT.resolve(
            "amz-service/amz-service-report/src/main/java/com/amz/mapper/ProfitDetailMapper.java");
    private static final Path SERVICE = ROOT.resolve(
            "amz-service/amz-service-report/src/main/java/com/amz/service/impl/ReportUpgradeServiceImpl.java");
    private static final Path MIGRATION = ROOT.resolve(
            "amz-service/amz-service-report/src/main/resources/db/migration/V1__init.sql");

    private static final List<String> USED_COLUMNS = List.of(
            "shop_id", "asin", "report_date", "product_sales", "product_cost", "advertising_cost",
            "fba_fees", "referral_fee", "variable_closing_fee", "storage_fee", "gross_profit", "net_profit");

    @Test
    @DisplayName("SQL 别名与服务读取键双向一致")
    void aliasesMatchTheKeysTheServiceReads() {
        Set<String> aliases = collect(Pattern.compile("AS\\s+([A-Za-z][A-Za-z0-9]*)"), summarySql(), 1);
        Set<String> keys = collect(Pattern.compile("\\(\\s*group,\\s*\"([A-Za-z][A-Za-z0-9]*)\""),
                read(SERVICE), 1);
        assertTrue(keys.contains("asin"), "服务应读取 asin 列，测试正则失效了：" + keys);

        Set<String> unknown = keys.stream().filter(k -> !aliases.contains(k)).collect(Collectors.toSet());
        assertTrue(unknown.isEmpty(), "服务读取了 SQL 未产出的别名：" + unknown);
        Set<String> unread = aliases.stream().filter(a -> !keys.contains(a)).collect(Collectors.toSet());
        assertTrue(unread.isEmpty(), "SQL 产出了服务不读的别名（要么删列要么补读）：" + unread);
    }

    @Test
    @DisplayName("SQL 引用的列都是实体真实映射的列，表名与 @TableName 一致")
    void columnsAndTableMatchEntityMapping() {
        Set<String> mapped = new LinkedHashSet<>();
        for (Field f : ProfitDetail.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            TableField tf = f.getAnnotation(TableField.class);
            mapped.add(tf != null && !tf.value().isEmpty() ? tf.value() : toSnakeCase(f.getName()));
        }
        for (String column : USED_COLUMNS) {
            assertTrue(mapped.contains(column),
                    "SQL 引用了实体未映射的列：" + column + "，实体列集=" + mapped);
        }

        String declared = Pattern.compile("FROM\\s+(amz_\\w+)").matcher(summarySql()).results()
                .map(r -> r.group(1)).findFirst().orElse("<无 FROM>");
        assertEquals(ProfitDetail.class.getAnnotation(TableName.class).value(), declared,
                "SQL 表名与 @TableName 不一致");
    }

    @Test
    @DisplayName("聚合必须绑定店铺、日期区间走 report_date，且不得回传整行")
    void aggregationIsScopedAndNarrow() {
        String sql = summarySql();
        assertTrue(Pattern.compile("WHERE\\s+shop_id\\s*=\\s*#\\{shopId\\}").matcher(sql).find(),
                "聚合必须绑定 shop_id，否则跨店利润会被合计进同一个报表");
        assertTrue(sql.contains("report_date &gt;=") || sql.contains("report_date >="),
                "起始日期必须落在 report_date 上");
        // 上下界都要钉住：只查下界时，把 endDate 绑到别的列（或干脆漏掉）也能全绿，
        // 而"指定一个月却聚合全店历史"正是这次下沉最容易犯的口径错误。
        assertTrue(sql.contains("report_date &lt;=") || sql.contains("report_date <="),
                "结束日期必须落在 report_date 上，否则区间上限形同不存在");
        assertTrue(sql.contains("GROUP BY asin"), "必须按 ASIN 分组");
        assertFalse(sql.toUpperCase().contains("SELECT *"), "聚合不得回传整行");
    }

    @Test
    @DisplayName("聚合走的 (shop_id, report_date) 索引在建表脚本里真实存在")
    void shopDateIndexExists() {
        String ddl = tableBlock(read(MIGRATION), "amz_profit_detail");
        assertTrue(Pattern.compile("INDEX\\s+\\w+\\s*\\(\\s*shop_id,\\s*report_date\\s*\\)")
                .matcher(ddl).find(),
                "amz_profit_detail 上没有 (shop_id, report_date) 索引，整店聚合会退化成全表扫描：" + ddl);
    }

    /** 取出 sumByAsin 上 @Select 的 SQL（拼接各字符串片段，去掉 <script> 包装）。 */
    private static String summarySql() {
        String source = read(MAPPER);
        int methodAt = source.indexOf("sumByAsin(");
        assertTrue(methodAt > 0, "mapper 里找不到 sumByAsin");
        int selectAt = source.lastIndexOf("@Select", methodAt);
        assertTrue(selectAt > 0, "sumByAsin 上没有 @Select");
        return Pattern.compile("\"([^\"]*)\"")
                .matcher(source.substring(selectAt, methodAt)).results()
                .map(r -> r.group(1))
                .collect(Collectors.joining())
                .replaceAll("</?script>", "");
    }

    /** 从建表脚本里截出指定表的 CREATE TABLE 语句块。 */
    private static String tableBlock(String ddl, String table) {
        int from = ddl.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        assertTrue(from >= 0, "建表脚本里找不到 " + table);
        int to = ddl.indexOf(";", from);
        return ddl.substring(from, to < 0 ? ddl.length() : to);
    }

    private static Set<String> collect(Pattern pattern, String text, int group) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(group));
        }
        return out;
    }

    private static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到契约文件：" + path, e);
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
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
