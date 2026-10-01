package com.amz.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账批量幂等写（ON DUPLICATE KEY UPDATE）的两条不可退让规则。
 * <p>
 * 语义本身已在隔离 MySQL 8.0.46 与 8.4 上对四种情形（有正短款 / 短款为 0 / 短款为 NULL /
 * 全新订单）实跑核对过。这里守的是<b>之后有人改 SQL 时把它改错</b>：
 * 短款是费用比对（T06）写进来的结论，一旦出现在更新列表里，重算台账就会把它清零，
 * "该索赔的钱"凭空消失，而且不会有任何报错。
 */
@DisplayName("台账 upsert SQL：短款不得被覆盖，状态仍要认旧短款")
class PaymentCollectionUpsertSqlContractTest {

    private static final Path MAPPER = repoRoot().resolve(
            "amz-service/amz-service-finance/src/main/java/com/amz/mapper/PaymentCollectionMapper.java");

    private static String upsertSql() {
        String source = read(MAPPER);
        int at = source.indexOf("upsertBatch(");
        assertTrue(at > 0, "mapper 里找不到 upsertBatch");
        int selectAt = source.lastIndexOf("@Insert", at);
        assertTrue(selectAt > 0, "upsertBatch 上没有 @Insert");
        return source.substring(selectAt, at).replaceAll("\"\\s*\\+?\\s*\"", "")
                .replaceAll("[\\r\\n\\s]+", " ");
    }

    @Test
    @DisplayName("更新列表里没有 shortfall：重算不得把费用比对的结论冲掉")
    void shortfallIsNeverOverwritten() {
        String sql = upsertSql();
        String updateClause = sql.substring(sql.indexOf("ON DUPLICATE KEY UPDATE"));
        assertFalse(updateClause.contains("shortfall = new.shortfall"),
                "短款出现在更新列表里：" + updateClause);
        assertFalse(updateClause.contains("shortfall=new.shortfall"), updateClause);
        assertTrue(sql.contains("INSERT INTO amz_payment_collection"), sql);
    }

    @Test
    @DisplayName("状态用旧行的短款判定，等价于原 applyStatusByShortfall")
    void statusFallsBackToExistingShortfall() {
        String sql = upsertSql();
        assertTrue(sql.contains("IF(shortfall IS NOT NULL AND shortfall &gt; 0, 'SHORTFALL', new.status)"),
                "缺少「有正短款则状态保持 SHORTFALL」这条规则：" + sql);
    }

    @Test
    @DisplayName("行别名语法需要 MySQL 8.0.19+，必须写在注释里")
    void mysqlVersionFloorIsDocumented() {
        assertTrue(read(MAPPER).contains("8.0.19"),
                "AS new 是 8.0.19+ 语法，部署到更早版本会语法报错，必须在方法注释里写明");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
