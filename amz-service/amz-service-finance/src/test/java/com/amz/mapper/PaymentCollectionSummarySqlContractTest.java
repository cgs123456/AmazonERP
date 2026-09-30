package com.amz.mapper;

import com.amz.model.PaymentCollection;
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
import java.util.Arrays;
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
 * 回款概览聚合 SQL 的静态契约。
 * <p>
 * SQL 一旦从 Java 挪进注解，编译期就再也检查不出它与实体/服务之间的一致性：
 * 常量改名、列改名、别名拼错、别名与读取键漂移，全都只在运行时炸。
 * 这里把这些关系逐条钉住（列名与状态字面量取自实体自身，不重复硬编码）。
 * <p>
 * 聚合语义本身（NULL 处理、分币种、跨店隔离）是在 MySQL 8.4 上对同一份 fixture
 * 实跑比对确认的，不是靠本文件推断出来的。
 */
@DisplayName("回款概览聚合 SQL 与实体/服务的静态契约")
class PaymentCollectionSummarySqlContractTest {

    private static final Path ROOT = repoRoot();
    private static final Path MAPPER = ROOT.resolve(
            "amz-service/amz-service-finance/src/main/java/com/amz/mapper/PaymentCollectionMapper.java");
    private static final Path SERVICE = ROOT.resolve(
            "amz-service/amz-service-finance/src/main/java/com/amz/service/impl/PaymentCollectionServiceImpl.java");

    /** 聚合 SQL 中引用的表列，必须都是实体真实映射出来的列。 */
    private static final List<String> USED_COLUMNS =
            List.of("shop_id", "currency", "status", "receivable", "net_received", "shortfall");

    @Test
    @DisplayName("SQL 里的状态字面量恰好等于实体 STATUS_* 常量的值")
    void statusLiteralsMatchEntityConstants() {
        Set<String> constants = Arrays.stream(PaymentCollection.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("STATUS_"))
                .map(f -> readStaticString(f))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertFalse(constants.isEmpty(), "实体上找不到 STATUS_* 常量，测试本身失效了");

        Set<String> inSql = new LinkedHashSet<>();
        Matcher m = Pattern.compile("status\\s*=\\s*'([A-Z_]+)'").matcher(summarySql());
        while (m.find()) {
            inSql.add(m.group(1));
        }
        assertEquals(constants, inSql,
                "SQL 状态字面量与 PaymentCollection.STATUS_* 常量漂移");
    }

    @Test
    @DisplayName("SQL 别名与服务读取键双向一致")
    void aliasesMatchTheKeysTheServiceReads() {
        Set<String> aliases = new LinkedHashSet<>();
        Matcher aliasMatcher = Pattern.compile("AS\\s+([A-Za-z][A-Za-z0-9]*)").matcher(summarySql());
        while (aliasMatcher.find()) {
            aliases.add(aliasMatcher.group(1));
        }

        String service = read(SERVICE);
        Set<String> keys = new LinkedHashSet<>();
        Matcher keyMatcher = Pattern.compile("\\(\\s*group,\\s*\"([A-Za-z][A-Za-z0-9]*)\"").matcher(service);
        while (keyMatcher.find()) {
            keys.add(keyMatcher.group(1));
        }
        assertFalse(keys.isEmpty(), "服务里读不到任何聚合键，测试本身失效了");

        Set<String> unknown = keys.stream().filter(k -> !aliases.contains(k)).collect(Collectors.toSet());
        assertTrue(unknown.isEmpty(), "服务读取了 SQL 未产出的别名：" + unknown);
        Set<String> unread = aliases.stream().filter(a -> !keys.contains(a)).collect(Collectors.toSet());
        assertTrue(unread.isEmpty(), "SQL 产出了服务不读的别名（要么删列要么补读，别留哑列）：" + unread);
    }

    @Test
    @DisplayName("SQL 引用的列都是实体真实映射的列，表名与 @TableName 一致")
    void columnsAndTableMatchEntityMapping() {
        Set<String> mapped = new LinkedHashSet<>();
        for (Field f : PaymentCollection.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            TableField tf = f.getAnnotation(TableField.class);
            if (tf != null && !tf.value().isEmpty()) {
                mapped.add(tf.value());
            } else {
                mapped.add(toSnakeCase(f.getName()));
            }
        }
        for (String column : USED_COLUMNS) {
            assertTrue(mapped.contains(column),
                    "SQL 引用了实体未映射的列：" + column + "，实体列集=" + mapped);
        }

        String declared = Pattern.compile("FROM\\s+(amz_\\w+)").matcher(summarySql()).results()
                .map(r -> r.group(1)).findFirst().orElse("<无 FROM>");
        TableName tableName = PaymentCollection.class.getAnnotation(TableName.class);
        assertEquals(tableName.value(), declared, "SQL 表名与 @TableName 不一致");
    }

    @Test
    @DisplayName("聚合必须按店铺过滤、按币种分组，且不得回传整行")
    void aggregationIsScopedAndNarrow() {
        String sql = summarySql();
        assertTrue(Pattern.compile("WHERE\\s+shop_id\\s*=\\s*#\\{shopId\\}").matcher(sql).find(),
                "聚合必须绑定 shop_id，否则跨店金额会被合计进同一个概览");
        assertTrue(sql.contains("GROUP BY currency"), "必须按币种分组，多币种不可静默相加");
        assertFalse(sql.toUpperCase().contains("SELECT *"), "概览不得回传整行");
    }

    @Test
    @DisplayName("聚合走的 shop_id 前缀索引在建表脚本里真实存在")
    void shopIdIndexExists() {
        Path migration = ROOT.resolve(
                "amz-service/amz-service-finance/src/main/resources/db/migration/V3__payment_collection.sql");
        String ddl = read(migration);
        assertTrue(Pattern.compile("INDEX\\s+\\w+\\s*\\(\\s*shop_id").matcher(ddl).find(),
                "amz_payment_collection 上没有以 shop_id 打头的索引，整店聚合会退化成全表扫描：" + ddl);
    }

    /** 取出 aggregateSummaryByCurrency 上 @Select 的 SQL（拼接各字符串片段）。 */
    private static String summarySql() {
        String source = read(MAPPER);
        int methodAt = source.indexOf("aggregateSummaryByCurrency");
        assertTrue(methodAt > 0, "mapper 里找不到 aggregateSummaryByCurrency");
        int selectAt = source.lastIndexOf("@Select", methodAt);
        assertTrue(selectAt > 0, "aggregateSummaryByCurrency 上没有 @Select");
        String block = source.substring(selectAt, methodAt);
        return Pattern.compile("\"([^\"]*)\"").matcher(block).results()
                .map(r -> r.group(1))
                .collect(Collectors.joining())
                // 注解里的 <script> 包装不属于 SQL 语义
                .replaceAll("</?script>", "");
    }

    private static String readStaticString(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法读取常量 " + field.getName(), e);
        }
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
