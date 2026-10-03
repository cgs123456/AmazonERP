package com.amz.model;

import com.amz.model.pojo.History;
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
 * History 实体与 amz_history 建表脚本的列名契约。
 * <p>
 * 动因：实体把搜索词字段声明成 {@code @TableField("history")}，而表里这一列叫
 * {@code keyword}（{@code V1__init.sql:5-10}，legacy 脚本 06-init-tables-search.sql 同名同形）。
 * 于是 {@code HistoryServiceImpl.getHistoryList} 的 selectList 必 1054 → 搜索页「搜索历史」500；
 * {@code SearchServiceImpl:102-115} 每次搜索都写这条记录，却被 try/catch 吞成 WARN，
 * 主流程看着成功、历史记录从此不再落库——mock 测试两条都看不见。
 */
@DisplayName("搜索历史实体列名契约")
class HistoryColumnContractTest {

    private static final String DDL = "db/migration/V1__init.sql";
    private static final Pattern CREATE_BLOCK = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS amz_history \\((.*?)\\n\\) ENGINE", Pattern.DOTALL);
    private static final Pattern COLUMN_DEF = Pattern.compile("^\\s{4}([a-z][a-z0-9_]*)\\s+[A-Za-z]");

    private static Set<String> ddlColumns;
    private static TableInfo tableInfo;

    @BeforeAll
    static void setUp() throws IOException {
        try (InputStream in = HistoryColumnContractTest.class.getClassLoader().getResourceAsStream(DDL)) {
            assertNotNull(in, DDL + " 必须在测试 classpath 上（Flyway 脚本随 jar 打包）");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher block = CREATE_BLOCK.matcher(sql);
            assertTrue(block.find(), "V1__init.sql 里必须能找到 amz_history 的建表块");
            ddlColumns = new LinkedHashSet<>();
            for (String line : block.group(1).split("\n")) {
                Matcher col = COLUMN_DEF.matcher(line.replace("\r", ""));
                if (col.find()) {
                    ddlColumns.add(col.group(1));
                }
            }
            assertFalse(ddlColumns.isEmpty(), "建表块解析出 0 列＝解析器失效，不能当作通过");
        }
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                History.class);
        tableInfo = TableInfoHelper.getTableInfo(History.class);
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
                "History 映射了 DDL 里不存在的列：" + drift + "；DDL 实际列=" + ddlColumns);
    }

    @Test
    @DisplayName("搜索词字段必须落在 keyword 列上，Java/JSON 字段名保持 history")
    void historyFieldMapsToTheKeywordColumn() {
        assertTrue(ddlColumns.contains("keyword"), "DDL 里必须真有 keyword 列，否则改映射也是错");
        String column = null;
        for (TableFieldInfo f : tableInfo.getFieldList()) {
            if (f.getProperty().equals("history")) {
                column = f.getColumn();
            }
        }
        assertEquals("keyword", column, "history 字段应映射到 keyword 列");
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
}
