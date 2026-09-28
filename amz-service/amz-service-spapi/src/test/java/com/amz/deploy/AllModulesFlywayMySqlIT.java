package com.amz.deploy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全仓 14 个库 x 49 个 Flyway 迁移，在真实 MySQL 8 上被 Flyway 依次执行通过的验收。
 *
 * <p>动机（2026-09-28 实测）：在此之前只有 amz_ad 一个库的迁移在 Flyway 下被真机验证过
 * （amz-service-ad 的 {@code AdMigrationMySqlIT}）；其余 13 个库的迁移只被
 * {@code tools/synthetic-data/apply_migrations.py} 以裸 SQL 执行过——那条路径绕过 Flyway，
 * 因此「版本号重复 / 应用顺序 / baseline 行为 / Flyway 专有语法」这类只在 Flyway 下才暴露的
 * 问题一直没有任何证据。它们在部署时表现为服务启动失败，是发布级事故，必须在 CI 里守住。
 *
 * <p>安全性：本 IT 只在 {@code <业务库名>_fwit} 的专用库上 DROP + CREATE，断言强制校验后缀，
 * 永远不会碰到 14 个业务库（里面有 22 万行合成数据集）。
 *
 * <p>触发方式：设置 FLYWAY_ALL_IT_URL / FLYWAY_ALL_IT_USER / FLYWAY_ALL_IT_PASSWORD 后跑整仓
 * {@code mvn test}（根 pom 的 surefire 额外收 *IT.java）。不设则整类跳过，
 * 因此没有 MySQL 的开发机不受影响。
 */
@EnabledIfEnvironmentVariable(named = "FLYWAY_ALL_IT_URL", matches = ".+")
class AllModulesFlywayMySqlIT {

    private static final Path ROOT = findRepoRoot();

    /** IT 专用库后缀：只能操作带这个后缀的库，防止误伤业务库。 */
    private static final String SCHEMA_SUFFIX = "_fwit";

    private static final String BASE_URL = System.getenv("FLYWAY_ALL_IT_URL");
    private static final String USER = System.getenv().getOrDefault("FLYWAY_ALL_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("FLYWAY_ALL_IT_PASSWORD", "");

    private static final Pattern JDBC_DB = Pattern.compile("jdbc:mysql://[^/]*/([A-Za-z0-9_]+)");
    private static final Pattern JDBC_BASE = Pattern.compile("^(jdbc:mysql://[^/?]+)/[^/?]*(\\?.*)?$");
    private static final Pattern CREATE_DATABASE =
            Pattern.compile("(?i)CREATE\\s+DATABASE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)");

    @Test
    @DisplayName("每个数据库模块的每个迁移都在 MySQL 8 上被 Flyway 执行成功")
    void everyMigrationAppliesUnderFlywayOnMySql8() throws Exception {
        List<ModuleMigration> modules = discoverModules();
        assertFalse(modules.isEmpty(), "未发现任何带 db/migration 的服务模块");

        // 双向对齐：部署脚本建的库集合 == 有迁移的服务模块集合。
        // 新增库或新增模块时任一侧漏配都会在这里红，而不是等到部署时服务起不来。
        assertEquals(databasesCreatedByDeployment(), databaseNamesOf(modules),
                "docker/init-sql 建的库集合必须与带 Flyway 迁移的服务模块一一对应");

        int appliedTotal = 0;
        int fileTotal = 0;
        for (ModuleMigration module : modules) {
            String schema = module.database() + SCHEMA_SUFFIX;
            assertTrue(schema.endsWith(SCHEMA_SUFFIX), "只允许操作 IT 专用库：" + schema);
            assertTrue(module.applicationYml().contains("baseline-on-migrate: true"),
                    module.name() + " 必须显式开启 Flyway baseline（P0-58）");

            recreateSchema(schema);
            MigrateResult result = Flyway.configure()
                    .dataSource(urlFor(schema), USER, PASSWORD)
                    .locations("filesystem:" + module.migrationDir().toString().replace("\\", "/"))
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .cleanDisabled(true)
                    .load()
                    .migrate();

            assertTrue(result.success, module.name() + " 的迁移执行失败");
            assertEquals(module.migrationFiles().size(), Math.toIntExact(result.migrationsExecuted),
                    module.name() + "：空库上必须执行全部迁移文件");
            appliedTotal += Math.toIntExact(result.migrationsExecuted);
            fileTotal += module.migrationFiles().size();

            try (Connection connection = DriverManager.getConnection(urlFor(schema), USER, PASSWORD)) {
                assertEquals(module.migrationFiles().size(), historyRows(connection),
                        module.name() + "：flyway_schema_history 行数应等于迁移文件数");
                assertEquals(0, failedHistoryRows(connection),
                        module.name() + "：flyway_schema_history 存在 success=0 的记录");
                assertTrue(tableCount(connection, schema) > 0,
                        module.name() + "：迁移后至少应建出一张表");
            }
        }

        assertEquals(fileTotal, appliedTotal, "迁移文件总数与执行总数必须相等");
        // 下限守卫：迁移只增不减，若有人误删迁移目录或文件，数字会掉下来。
        assertTrue(appliedTotal >= 49, "迁移总数不应减少，当前 " + appliedTotal);
    }

    // ------------------------------------------------------------------
    // 仓库扫描（模块 -> 库名 全部从配置推导，不硬编码）
    // ------------------------------------------------------------------

    private record ModuleMigration(String name, String database, Path migrationDir,
                                   List<Path> migrationFiles, String applicationYml) {
    }

    private static List<ModuleMigration> discoverModules() throws IOException {
        List<ModuleMigration> modules = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(ROOT.resolve("amz-service"))) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path migrationDir = dir.resolve("src/main/resources/db/migration");
                if (!Files.isDirectory(migrationDir)) {
                    continue;
                }
                Path yml = dir.resolve("src/main/resources/application.yml");
                assertTrue(Files.isRegularFile(yml), "缺少 application.yml：" + yml);
                String ymlText = Files.readString(yml, StandardCharsets.UTF_8);
                List<Path> files = migrationFiles(migrationDir);
                modules.add(new ModuleMigration(dir.getFileName().toString(), databaseOf(ymlText),
                        migrationDir, files, ymlText));
            }
        }
        return modules;
    }

    private static List<Path> migrationFiles(Path migrationDir) throws IOException {
        try (Stream<Path> files = Files.list(migrationDir)) {
            List<Path> sqlFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            assertFalse(sqlFiles.isEmpty(), "迁移目录为空：" + migrationDir);
            return sqlFiles;
        }
    }

    private static String databaseOf(String applicationYml) {
        Matcher matcher = JDBC_DB.matcher(applicationYml);
        assertTrue(matcher.find(),
                "application.yml 里找不到 jdbc:mysql 的库名（模块 -> 库 的映射依赖它）");
        return matcher.group(1);
    }

    private static Set<String> databaseNamesOf(List<ModuleMigration> modules) {
        Set<String> names = new TreeSet<>();
        for (ModuleMigration module : modules) {
            names.add(module.database());
        }
        return names;
    }

    /** 部署路径（docker/init-sql）实际会建出来的库集合，从脚本原文解析。 */
    private static Set<String> databasesCreatedByDeployment() throws IOException {
        Set<String> names = new TreeSet<>();
        Path initSql = ROOT.resolve("docker/init-sql");
        assertTrue(Files.isDirectory(initSql), "缺少 docker/init-sql：" + initSql);
        try (Stream<Path> files = Files.list(initSql)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                Matcher matcher = CREATE_DATABASE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        assertFalse(names.isEmpty(), "docker/init-sql 里没有 CREATE DATABASE");
        return names;
    }

    // ------------------------------------------------------------------
    // 数据库操作
    // ------------------------------------------------------------------

    private static void recreateSchema(String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + schema + "`");
            statement.executeUpdate("CREATE DATABASE `" + schema
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static String urlFor(String schema) {
        Matcher matcher = JDBC_BASE.matcher(BASE_URL);
        assertTrue(matcher.matches(),
                "FLYWAY_ALL_IT_URL 必须形如 jdbc:mysql://host:port/db?params：" + BASE_URL);
        return matcher.group(1) + "/" + schema + (matcher.group(2) == null ? "" : matcher.group(2));
    }

    private static String serverUrl() {
        Matcher matcher = JDBC_BASE.matcher(BASE_URL);
        assertTrue(matcher.matches(),
                "FLYWAY_ALL_IT_URL 必须形如 jdbc:mysql://host:port/db?params：" + BASE_URL);
        return matcher.group(1) + "/" + (matcher.group(2) == null ? "" : matcher.group(2));
    }

    private static int historyRows(Connection connection) throws SQLException {
        return scalarInt(connection, "SELECT COUNT(*) FROM flyway_schema_history");
    }

    private static int failedHistoryRows(Connection connection) throws SQLException {
        return scalarInt(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0");
    }

    private static int tableCount(Connection connection, String schema) throws SQLException {
        return scalarInt(connection, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = '" + schema + "'");
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "查询应返回一行：" + sql);
            return resultSet.getInt(1);
        }
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
