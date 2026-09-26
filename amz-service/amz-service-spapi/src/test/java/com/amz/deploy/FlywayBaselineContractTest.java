package com.amz.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护 Flyway 作为表结构唯一事实源，以及两条部署路径的建库集合一致。
 */
class FlywayBaselineContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Set<String> DATABASE_MODULES = Set.of(
            "ad", "ai", "customer", "finance", "logistics", "multiplatform",
            "ops", "order", "procurement", "product", "report", "search",
            "spapi", "user");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-z0-9_]+)`?");
    private static final Pattern CREATE_DATABASE = Pattern.compile(
            "(?i)CREATE\\s+DATABASE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)");
    private static final Pattern MYSQL_UNSUPPORTED_CONDITIONAL_DDL = Pattern.compile(
            "(?i)\\b(?:ADD|DROP)\\s+COLUMN\\s+IF\\s+(?:NOT\\s+)?EXISTS\\b"
                    + "|\\b(?:CREATE|DROP)\\s+INDEX\\s+IF\\s+(?:NOT\\s+)?EXISTS\\b");
    private static final Pattern UNQUOTED_RANK_COLUMN = Pattern.compile(
            "(?im)^\\s*`?rank`?\\s+(?:INT|BIGINT|SMALLINT|TINYINT)\\b");

    @Test
    void allFourteenDatabaseModulesExplicitlyConfigureFlywayBaseline() throws IOException {
        for (String module : DATABASE_MODULES) {
            Path yml = ROOT.resolve("amz-service/amz-service-" + module
                    + "/src/main/resources/application.yml");
            assertTrue(Files.isRegularFile(yml), "缺少 application.yml：" + yml);
            String text = Files.readString(yml, StandardCharsets.UTF_8);
            assertTrue(text.contains("  flyway:"), module + " 缺少 spring.flyway 配置");
            assertTrue(text.contains("baseline-on-migrate: true"),
                    module + " 必须显式开启 Flyway baseline");
            assertTrue(text.contains("baseline-version: 1"),
                    module + " 必须显式固定 baseline-version");
            assertTrue(text.contains("locations: classpath:db/migration"),
                    module + " 必须显式限定迁移目录");
        }
    }

    @Test
    void reportModuleHasItsOwnDatasource() throws IOException {
        String yml = Files.readString(ROOT.resolve(
                "amz-service/amz-service-report/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/amz_report?"),
                "report 有 6 个 BaseMapper，必须连接 amz_report");
        assertTrue(yml.contains("username: ${DB_USERNAME:root}"));
        assertTrue(yml.contains("password: ${DB_PASSWORD:}"));
        assertFalse(yml.contains("无独立数据库"), "不能保留与代码事实冲突的注释");
    }

    @Test
    void flywayRemainsTheOnlyTableCreationEntryPoint() throws IOException {
        List<Path> migrationFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("amz-service"))) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/')
                            .matches(".*/src/main/resources/db/migration/[^/]+\\.sql$"))
                    .forEach(migrationFiles::add);
        }
        assertTrue(migrationFiles.size() > 0, "迁移扫描不能空通过");

        Map<String, Integer> tableCounts = new HashMap<>();
        for (Path migration : migrationFiles) {
            Matcher matcher = CREATE_TABLE.matcher(Files.readString(migration, StandardCharsets.UTF_8));
            while (matcher.find()) {
                tableCounts.merge(matcher.group(1).toLowerCase(), 1, Integer::sum);
            }
        }
        assertEquals(112, tableCounts.size(), "Flyway 唯一表集合必须保持 112 张（V6-V8 新增 3 张通知表）");
        assertEquals(112, tableCounts.values().stream().mapToInt(Integer::intValue).sum(),
                "Flyway 迁移不得重复建表");

        String composeSql = Files.readString(ROOT.resolve("docker/init-sql/01-init-databases.sql"),
                StandardCharsets.UTF_8);
        String k8sJob = Files.readString(ROOT.resolve("k8s/infra/mysql-init-job.yaml"),
                StandardCharsets.UTF_8);
        assertFalse(CREATE_TABLE.matcher(composeSql).find(), "Compose 初始化脚本不得建表");
        assertFalse(CREATE_TABLE.matcher(k8sJob).find(), "k8s Job 不得建表");
    }

    @Test
    void bothDeploymentPathsCreateExactlyTheSameFourteenSchemas() throws IOException {
        Set<String> composeDbs = createDatabases(Files.readString(
                ROOT.resolve("docker/init-sql/01-init-databases.sql"), StandardCharsets.UTF_8));
        Set<String> k8sDbs = createDatabases(Files.readString(
                ROOT.resolve("k8s/infra/mysql-init-job.yaml"), StandardCharsets.UTF_8));
        assertEquals(14, composeDbs.size(), "Compose 必须创建 14 个库");
        assertEquals(composeDbs, k8sDbs, "Compose 与 k8s 建库集合必须完全一致");
    }

    @Test
    void migrationsUseMysql8CompatibleConditionalDdl() throws IOException {
        for (Path migration : migrationFiles()) {
            Matcher matcher = MYSQL_UNSUPPORTED_CONDITIONAL_DDL.matcher(stripSqlComments(read(migration)));
            assertFalse(matcher.find(), "MySQL 8 不支持条件 DDL：" + migration);
        }
    }

    @Test
    void keywordRankAvoidsMysqlReservedColumnName() throws IOException {
        Path migration = ROOT.resolve("amz-service/amz-service-ops/src/main/resources/db/migration/V1__init.sql");
        String sql = stripSqlComments(read(migration));
        assertFalse(UNQUOTED_RANK_COLUMN.matcher(sql).find(),
                "rank 是 MySQL 8 保留字，物理列必须改名或稳定转义：" + migration);
        assertTrue(sql.contains("rank_position INT"), "关键词排名物理列必须使用 rank_position");

        String model = read(ROOT.resolve(
                "amz-service/amz-service-ops/src/main/java/com/amz/model/KeywordRankRecord.java"));
        assertTrue(model.contains("@TableField(\"rank_position\")"),
                "实体必须显式映射 rank_position，同时保留 API 字段 rank");
    }

    @Test
    void inventoryAlertSchemaSupportsShopLevelRules() throws IOException {
        String sql = read(ROOT.resolve(
                "amz-service/amz-service-logistics/src/main/resources/db/migration/V1__init.sql"));
        assertTrue(sql.contains("sku VARCHAR(64) DEFAULT NULL COMMENT 'SKU（NULL=店铺级规则"),
                "amz_inventory_alert.sku 必须允许 NULL，否则店铺级预警种子数据无法迁移");
    }

    @Test
    void upgradeColumnsAreDefinedInInitialCreateStatements() throws IOException {
        String procurement = stripSqlComments(read(ROOT.resolve(
                "amz-service/amz-service-procurement/src/main/resources/db/migration/V1__init.sql")));
        String purchaseOrder = procurement.substring(
                procurement.indexOf("CREATE TABLE IF NOT EXISTS amz_purchase_order"),
                procurement.indexOf("CREATE TABLE IF NOT EXISTS amz_quality_check"));
        assertTrue(purchaseOrder.contains("supplier_id BIGINT DEFAULT NULL COMMENT '供应商 ID'"));
        assertTrue(purchaseOrder.contains("plan_id BIGINT DEFAULT NULL COMMENT '关联采购计划 ID'"));
        assertTrue(purchaseOrder.contains("total_quantity INT DEFAULT NULL COMMENT '总数量'"));
        assertTrue(purchaseOrder.contains("currency VARCHAR(10) DEFAULT 'CNY' COMMENT '币种'"));
        assertFalse(procurement.contains("ALTER TABLE amz_purchase_order"));

        String user = stripSqlComments(read(ROOT.resolve(
                "amz-service/amz-service-user/src/main/resources/db/migration/V1__init.sql")));
        String userTable = user.substring(
                user.indexOf("CREATE TABLE IF NOT EXISTS amz_user"),
                user.indexOf("CREATE TABLE IF NOT EXISTS amz_attention"));
        assertTrue(userTable.contains("role VARCHAR(50) NOT NULL DEFAULT 'VIEWER'"),
                "amz_user.role 必须直接进入初始建表语句");
        assertFalse(user.contains("ALTER TABLE amz_user"));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    private static List<Path> migrationFiles() throws IOException {
        List<Path> migrationFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("amz-service"))) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/')
                            .matches(".*/src/main/resources/db/migration/[^/]+\\.sql$"))
                    .forEach(migrationFiles::add);
        }
        assertTrue(migrationFiles.size() > 0, "迁移扫描不能空通过");
        return migrationFiles;
    }

    private static String stripSqlComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "");
    }
    private static Set<String> createDatabases(String text) {
        Matcher matcher = CREATE_DATABASE.matcher(text);
        return matcher.results().map(result -> result.group(1).toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("docker/init-sql"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
