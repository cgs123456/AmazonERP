package com.amz.service.impl;

import com.amz.model.pojo.User;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ShopServiceMySqlIT} 手写 amz_user DDL 与 {@link User} 实体的列契约。
 * <p>
 * <b>为什么需要它</b>：该 IT 是 {@code SHOP_IT_URL} 门控的真机用例——本机无 DB 时整类跳过，
 * 只有 CI 真跑。它用**手写**的 CREATE TABLE 建表，而 User 实体的 MyBatis 查询会 select
 * 实体声明的每一列；两者一漂移就是 {@code Unknown column 'xxx' in 'field list'}`，
 * 且**本机永远看不到**（跳过）——已发生两次：
 * <ul>
 *   <li>2026-10-11 漏 V4 的 {@code status}；</li>
 *   <li>2026-10-14 漏 V1 的 {@code create_time}（给 User 补该字段时暴露）。</li>
 * </ul>
 * 本测试是**静态**的（读源码文本 + 读实体映射，不起 DB），因此本机能跑、能提前挡住第三次。
 * 根治方向仍是让该 IT 直接应用 {@code db/migration} 而非手写 DDL；在那之前用本契约兜底。
 */
@DisplayName("ShopServiceMySqlIT 手写 DDL 与 User 实体的列契约")
class ShopItUserDdlContractTest {

    /** 从 IT 源码里抽出的手写 amz_user 建表块。 */
    // 含开头的双引号：字符串字面量要成对匹配，必须从第一个字面量的**开引号**开始切片，
    // 否则首个引号是「前一个字面量的闭引号」，会把 `+` 当成列名（本测试首轮实测过）。
    private static final String USER_CREATE_START = "\"CREATE TABLE amz_user (";
    private static final String USER_CREATE_END = "ENGINE=InnoDB";
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Set<String> NON_COLUMN_HEAD = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "FULLTEXT", "CHECK");

    private static Set<String> ddlColumns;
    private static TableInfo tableInfo;

    @BeforeAll
    static void setUp() throws IOException {
        Path it = locateShopIt();
        String src = Files.readString(it, StandardCharsets.UTF_8);
        // 建表语句是 Java 字符串拼接：取 "CREATE TABLE amz_user (" 到 "ENGINE=InnoDB" 之间的源码片段，
        // 把其中的字符串字面量取出拼回 SQL，再解析列名。
        // 不能直接对源码正则列名——源码里每个片段都带 `+ "` 前缀与换行。
        int start = src.indexOf(USER_CREATE_START);
        assertTrue(start >= 0,
                "ShopServiceMySqlIT 里必须能找到手写 amz_user 建表块（找不到＝解析器失效，不能当通过）");
        int end = src.indexOf(USER_CREATE_END, start);
        assertTrue(end > start, "找不到 amz_user 建表块的收尾 ENGINE=InnoDB（解析器失效）");
        String createFragment = src.substring(start, end);
        StringBuilder sql = new StringBuilder();
        Matcher q = QUOTED.matcher(createFragment);
        while (q.find()) {
            sql.append(q.group(1)).append(' ');
        }
        // 只取列定义体（第一对括号内）：拼回后的 SQL 形如
        // `CREATE TABLE amz_user ( id ... , ... )`，若连外层括号一起切分，
        // 首个 `(` 会把 depth 抬到 1，所有逗号都不再是顶层，整段被当成一列（本测试实测过）。
        String assembled = sql.toString();
        int open = assembled.indexOf('(');
        int close = assembled.lastIndexOf(')');
        assertTrue(open >= 0 && close > open,
                "手写 amz_user DDL 拼回后没有配对的括号＝解析器失效，不能当作通过");
        ddlColumns = parseColumns(assembled.substring(open + 1, close));
        assertFalse(ddlColumns.isEmpty(),
                "手写 amz_user DDL 解析出 0 列＝解析器失效，不能当作通过");

        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                User.class);
        tableInfo = TableInfoHelper.getTableInfo(User.class);
        assertNotNull(tableInfo);
    }

    @Test
    @DisplayName("User 实体映射的每一列都必须出现在手写 DDL 里（漏列 = 本机绿、CI 红）")
    void everyMappedColumnExistsInHandWrittenDdl() {
        List<String> drift = new ArrayList<>();
        for (String column : mappedColumns()) {
            if (!ddlColumns.contains(column)) {
                drift.add(column);
            }
        }
        assertTrue(drift.isEmpty(),
                "User 映射了手写 DDL 里不存在的列：" + drift + "；DDL 实际列=" + ddlColumns
                        + "。请把 ShopServiceMySqlIT 的 CREATE TABLE amz_user 补到与实体一致"
                        + "（本 IT 是 DB 门控的，本机跳过、只有 CI 会红）。");
    }

    private Set<String> mappedColumns() {
        Set<String> cols = new LinkedHashSet<>();
        if (tableInfo.getKeyColumn() != null) {
            cols.add(tableInfo.getKeyColumn());
        }
        for (TableFieldInfo f : tableInfo.getFieldList()) {
            cols.add(f.getColumn());
        }
        return cols;
    }

    /** 顶层逗号切分，取每段首个 token 作列名（跳过 KEY/INDEX/PRIMARY 等表级约束）。 */
    private static Set<String> parseColumns(String body) {
        Set<String> cols = new LinkedHashSet<>();
        int depth = 0;
        StringBuilder seg = new StringBuilder();
        List<String> segments = new ArrayList<>();
        for (char c : body.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                segments.add(seg.toString());
                seg.setLength(0);
            } else {
                seg.append(c);
            }
        }
        segments.add(seg.toString());
        for (String raw : segments) {
            String segment = raw.trim();
            if (segment.isEmpty()) continue;
            String head = segment.split("\\s+")[0].toUpperCase();
            if (NON_COLUMN_HEAD.contains(head)) continue;
            cols.add(segment.split("\\s+")[0]);
        }
        return cols;
    }

    private static Path locateShopIt() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "amz-service/amz-service-user/src/test/java/com/amz/service/impl/ShopServiceMySqlIT.java");
            if (Files.exists(candidate)) {
                return candidate;
            }
            // 也可能已经站在 amz-service-user 模块目录里（surefire 的 working dir 通常是模块根）
            Path local = current.resolve("src/test/java/com/amz/service/impl/ShopServiceMySqlIT.java");
            if (Files.exists(local)) {
                return local;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位 ShopServiceMySqlIT.java，user.dir="
                + System.getProperty("user.dir"));
    }
}
