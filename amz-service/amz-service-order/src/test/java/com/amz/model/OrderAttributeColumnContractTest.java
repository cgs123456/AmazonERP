package com.amz.model;

import com.amz.model.pojo.OrderAttribute;
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
 * OrderAttribute 实体与 amz_order_attribute 建表脚本的列名契约。
 * <p>
 * 动因：实体的属性名列叫 {@code label}，而表里这一列叫 {@code name}
 * （{@code V1__init.sql:8-14}，legacy 脚本 05-init-tables-order.sql 同样是 name）。
 * {@code OrderServiceImpl:423-427} 每个订单属性都 {@code insert} 一条，SQL 里带的就是不存在的
 * {@code label} → 1054。订单属性因此从来没写进过库，而读取侧不会报错（只 insert），
 * 所以这个缺陷在没有真库的 mock 测试里完全隐形。
 */
@DisplayName("订单属性实体列名契约")
class OrderAttributeColumnContractTest {

    private static final String DDL = "db/migration/V1__init.sql";
    private static final Pattern CREATE_BLOCK = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS amz_order_attribute \\((.*?)\\n\\) ENGINE", Pattern.DOTALL);
    private static final Pattern COLUMN_DEF = Pattern.compile("^\\s{4}([a-z][a-z0-9_]*)\\s+[A-Za-z]");

    private static Set<String> ddlColumns;
    private static TableInfo tableInfo;

    @BeforeAll
    static void setUp() throws IOException {
        try (InputStream in = OrderAttributeColumnContractTest.class.getClassLoader()
                .getResourceAsStream(DDL)) {
            assertNotNull(in, DDL + " 必须在测试 classpath 上（Flyway 脚本随 jar 打包）");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher block = CREATE_BLOCK.matcher(sql);
            assertTrue(block.find(), "V1__init.sql 里必须能找到 amz_order_attribute 的建表块");
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
                OrderAttribute.class);
        tableInfo = TableInfoHelper.getTableInfo(OrderAttribute.class);
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
                "OrderAttribute 映射了 DDL 里不存在的列：" + drift + "；DDL 实际列=" + ddlColumns);
    }

    @Test
    @DisplayName("属性名列必须对齐 name，Java/JSON 字段仍叫 label")
    void labelFieldMapsToTheNameColumn() {
        assertTrue(ddlColumns.contains("name"), "DDL 里必须真有 name 列，否则改映射也是错");
        assertFalse(ddlColumns.contains("label"), "DDL 里没有 label 列，映射成 label 就是 1054");
        assertEquals("name", columnOf("label"));
    }

    private String columnOf(String property) {
        for (TableFieldInfo f : tableInfo.getFieldList()) {
            if (f.getProperty().equals(property)) {
                return f.getColumn();
            }
        }
        return null;
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
