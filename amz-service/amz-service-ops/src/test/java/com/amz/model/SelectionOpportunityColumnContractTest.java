package com.amz.model;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选品实体与 amz_selection_opportunity 建表脚本的列名契约。
 * <p>
 * 动因：实体字段 trend30d/trend90d 没写 @TableField，MyBatis-Plus 的驼峰转下划线
 * 不会在数字前加下划线，生成的 SQL 引用的是 {@code trend30d}，而 DDL 里的列叫
 * {@code trend_30d} —— 选品页每次读写这两列都会以 MySQL 1054 失败，且 mock 测试全绿。
 * 这里把「实体映射出来的列必须存在于建表脚本」变成可执行的闸，而不靠人眼。
 */
@DisplayName("选品实体列名契约")
class SelectionOpportunityColumnContractTest {

    private static final String DDL = "db/migration/V1__init.sql";
    private static final Pattern CREATE_BLOCK =
            Pattern.compile("CREATE TABLE IF NOT EXISTS amz_selection_opportunity \\((.*?)\\n\\) ENGINE",
                    Pattern.DOTALL);
    // 只认小写开头的列定义，避免把 INDEX/UNIQUE/PRIMARY 的关键行当列收进来
    private static final Pattern COLUMN_DEF = Pattern.compile("^\\s{4}([a-z][a-z0-9_]*)\\s+[A-Za-z]");

    private static Set<String> ddlColumns;
    private static TableInfo tableInfo;

    @BeforeAll
    static void setUp() throws IOException {
        try (InputStream in = SelectionOpportunityColumnContractTest.class.getClassLoader()
                .getResourceAsStream(DDL)) {
            assertNotNull(in, DDL + " 必须在测试 classpath 上（Flyway 脚本随 jar 打包）");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher block = CREATE_BLOCK.matcher(sql);
            assertTrue(block.find(), "V1__init.sql 里必须能找到 amz_selection_opportunity 的建表块");
            ddlColumns = new LinkedHashSet<>();
            for (String line : block.group(1).split("\n")) {
                Matcher col = COLUMN_DEF.matcher(line.replace("\r", ""));
                if (col.find()) {
                    ddlColumns.add(col.group(1));
                }
            }
            assertFalse(ddlColumns.isEmpty(), "建表块解析出 0 列＝解析器失效，不能当作通过");
        }
        // 纯单测没有 MyBatis 启动流程，手动注册才能拿到 MP 真实解析出的列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SelectionOpportunity.class);
        tableInfo = TableInfoHelper.getTableInfo(SelectionOpportunity.class);
        assertNotNull(tableInfo);
    }

    @Test
    @DisplayName("实体映射出来的每一个列名都必须在建表脚本里存在")
    void everyMappedColumnExistsInDdl() {
        List<String> drift = new ArrayList<>();
        for (String column : mappedColumns()) {
            if (!ddlColumns.contains(column)) {
                drift.add(column);
            }
        }
        assertTrue(drift.isEmpty(),
                "SelectionOpportunity 映射了 DDL 里不存在的列：" + drift
                        + "；DDL 实际列=" + ddlColumns);
    }

    @Test
    @DisplayName("趋势两列必须显式对齐 trend_30d/trend_90d，不能靠驼峰推断")
    void trendColumnsUseTheDdlNames() {
        assertEquals("trend_30d", mappedColumnOf("trend30d"));
        assertEquals("trend_90d", mappedColumnOf("trend90d"));
    }

    /** 含主键列：漂移检查要覆盖全字段，而不是只看 @TableField 那部分。 */
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

    private String mappedColumnOf(String fieldName) {
        for (TableFieldInfo f : tableInfo.getFieldList()) {
            if (f.getProperty().equals(fieldName)) {
                return f.getColumn();
            }
        }
        return null;
    }
}
