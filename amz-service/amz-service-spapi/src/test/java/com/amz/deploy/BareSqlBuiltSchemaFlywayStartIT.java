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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住「用裸 SQL 建出来的库，服务仍然能启动」这条不变量。
 *
 * <p>背景（2026-09-28 实测缺陷）：{@code tools/synthetic-data/apply_migrations.py} 用 mysql
 * 客户端依次执行每个 {@code V*.sql}，这条路不经过 Flyway，因此建出来的库里没有
 * {@code flyway_schema_history}。而 14 个服务都配了 {@code baseline-on-migrate: true} +
 * {@code baseline-version: 1}：服务首次启动时 Flyway 看到「非空库 + 无历史表」就在 v1 打基线，
 * 然后把 V2..Vn 重放一遍 —— V2 立刻撞上已存在的对象报 Duplicate key name（amz_ad 实测
 * SQL State 42000），服务启动失败。
 *
 * <p>修复：裸 SQL 建完库后补一条 baseline 行，版本打到该模块的<b>最大</b>版本，等于告诉 Flyway
 * 「这些迁移都已经应用过了」。本 IT 复现这条路径并断言 Flyway 能干净启动（applied=0）。
 *
 * <p>同时做源码级守卫：{@code apply_migrations.py} 必须仍然包含这段基线逻辑，否则有人删掉它
 * 之后合成数据环境又会变成起不来的库。
 *
 * <p>触发方式与 {@link AllModulesFlywayMySqlIT} 相同（复用 FLYWAY_ALL_IT_* 环境变量）。
 */
@EnabledIfEnvironmentVariable(named = "FLYWAY_ALL_IT_URL", matches = ".+")
class BareSqlBuiltSchemaFlywayStartIT {

    private static final Path ROOT = findRepoRoot();

    /** 本 IT 专用库后缀：只能操作带这个后缀的库，绝不碰 14 个业务库。 */
    private static final String SCHEMA_SUFFIX = "_bsit";

    private static final String BASE_URL = System.getenv("FLYWAY_ALL_IT_URL");
    private static final String USER = System.getenv().getOrDefault("FLYWAY_ALL_IT_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("FLYWAY_ALL_IT_PASSWORD", "");

    private static final Pattern JDBC_DB = Pattern.compile("jdbc:mysql://[^/]*/([A-Za-z0-9_]+)");
    private static final Pattern JDBC_BASE = Pattern.compile("^(jdbc:mysql://[^/?]+)/[^/?]*(\\?.*)?$");
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Test
    @DisplayName("裸 SQL 建出的库在补上 Flyway baseline 后，服务能正常启动（不重放任何迁移）")
    void flywayStartsCleanlyOnSchemasBuiltWithRawSql() throws Exception {
        Path script = ROOT.resolve("tools/synthetic-data/apply_migrations.py");
        assertTrue(Files.isRegularFile(script), "缺少 tools/synthetic-data/apply_migrations.py");
        String scriptText = Files.readString(script, StandardCharsets.UTF_8);
        assertTrue(scriptText.contains("ensure_flyway_baseline"),
                "apply_migrations.py 必须在裸 SQL 之后写 Flyway baseline，否则服务启动会失败");
        assertTrue(scriptText.contains("<< Flyway Baseline >>"),
                "apply_migrations.py 的 baseline 行格式必须保持不变");

        List<Module> modules = discoverModules();
        for (Module module : modules) {
            String schema = module.database() + SCHEMA_SUFFIX;
            assertTrue(schema.endsWith(SCHEMA_SUFFIX), "只允许操作 IT 专用库：" + schema);
            recreateSchema(schema);

            // 第一步：像 apply_migrations.py 那样用裸 SQL 依次执行每个迁移
            try (Connection connection = DriverManager.getConnection(urlFor(schema), USER, PASSWORD);
                 Statement statement = connection.createStatement()) {
                for (Path file : module.migrationFiles()) {
                    statement.execute(Files.readString(file, StandardCharsets.UTF_8));
                }
                // 第二步：补 baseline 行，版本打到该模块最大版本
                statement.execute("CREATE TABLE IF NOT EXISTS flyway_schema_history ("
                        + " installed_rank int NOT NULL,"
                        + " version varchar(50) DEFAULT NULL,"
                        + " description varchar(200) NOT NULL,"
                        + " type varchar(20) NOT NULL,"
                        + " script varchar(1000) NOT NULL,"
                        + " checksum int DEFAULT NULL,"
                        + " installed_by varchar(100) NOT NULL,"
                        + " installed_on timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " execution_time int NOT NULL,"
                        + " success tinyint(1) NOT NULL,"
                        + " PRIMARY KEY (installed_rank),"
                        + " KEY flyway_schema_history_s_idx (success)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
                statement.executeUpdate("INSERT INTO flyway_schema_history (installed_rank, version,"
                        + " description, type, script, checksum, installed_by, execution_time, success)"
                        + " VALUES (1, '" + module.maxVersion()
                        + "', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>',"
                        + " NULL, USER(), 0, 1)");

                // 裸 SQL 路径不应该留下失败记录
                assertEquals(0, failedHistoryRows(connection),
                        module.name() + "：裸 SQL 建库后不应有失败的迁移记录");
                // 即时校验：baseline 行必须真的落库，否则后面 Flyway 会把库当成空库并重放迁移。
                // 这一条断言存在的意义是把失败点前移：没有它时，失败只表现为 Flyway 里一个
                // 很难读懂的 "Duplicate key"，看不出到底是没写 baseline 还是写错了库。
                assertEquals(1, baselineRows(connection),
                        module.name() + "：baseline 行未写入 flyway_schema_history");
                try (Connection verify = DriverManager.getConnection(urlFor(schema), USER, PASSWORD)) {
                    assertEquals(1, baselineRows(verify),
                            module.name() + "：新连接看不到 baseline 行（事务未提交或写到了别的库）");
                }
            }

            // 第三步：服务启动 —— Flyway 必须认定当前版本就是最大版本，一个迁移都不重放
            MigrateResult result = Flyway.configure()
                    .dataSource(urlFor(schema), USER, PASSWORD)
                    .locations("filesystem:" + module.migrationDir().toString().replace("\\", "/"))
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .cleanDisabled(true)
                    .load()
                    .migrate();

            assertTrue(result.success, module.name() + "：服务启动时 Flyway 失败（库会起不来）");
            assertEquals(0, Math.toIntExact(result.migrationsExecuted),
                    module.name() + "：已建好的库上不应该再重放任何迁移");

            try (Connection connection = DriverManager.getConnection(urlFor(schema), USER, PASSWORD)) {
                assertEquals(0, failedHistoryRows(connection),
                        module.name() + "：启动后出现 success=0 的迁移记录");
                assertEquals(module.maxVersion(), currentVersion(connection),
                        module.name() + "：Flyway 认定的当前版本应等于最大迁移版本");
            }
        }
    }

    // ------------------------------------------------------------------
    // 仓库扫描（模块 -> 库名从 application.yml 推导，不硬编码）
    // ------------------------------------------------------------------

    private record Module(String name, String database, Path migrationDir,
                          List<Path> migrationFiles, int maxVersion) {
    }

    private static List<Module> discoverModules() throws IOException {
        List<Module> modules = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(ROOT.resolve("amz-service"))) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path migrationDir = dir.resolve("src/main/resources/db/migration");
                if (!Files.isDirectory(migrationDir)) {
                    continue;
                }
                Path yml = dir.resolve("src/main/resources/application.yml");
                assertTrue(Files.isRegularFile(yml), "缺少 application.yml：" + yml);
                String ymlText = Files.readString(yml, StandardCharsets.UTF_8);
                Matcher matcher = JDBC_DB.matcher(ymlText);
                assertTrue(matcher.find(), "application.yml 里找不到 jdbc:mysql 库名：" + yml);

                List<Path> files = new ArrayList<>();
                int maxVersion = 0;
                try (Stream<Path> list = Files.list(migrationDir)) {
                    for (Path file : list.sorted().toList()) {
                        Matcher fileMatcher = MIGRATION_FILE.matcher(file.getFileName().toString());
                        if (fileMatcher.matches()) {
                            files.add(file);
                            maxVersion = Math.max(maxVersion, Integer.parseInt(fileMatcher.group(1)));
                        }
                    }
                }
                assertFalse(files.isEmpty(), "迁移目录为空：" + migrationDir);
                modules.add(new Module(dir.getFileName().toString(), matcher.group(1),
                        migrationDir, files, maxVersion));
            }
        }
        assertFalse(modules.isEmpty(), "未发现任何带 db/migration 的服务模块");
        return modules;
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

    /** 裸 SQL 需要一次执行多条语句，所以补上 allowMultiQueries。 */
    private static String urlFor(String schema) {
        Matcher matcher = JDBC_BASE.matcher(BASE_URL);
        assertTrue(matcher.matches(),
                "FLYWAY_ALL_IT_URL 必须形如 jdbc:mysql://host:port/db?params：" + BASE_URL);
        String query = matcher.group(2) == null ? "" : matcher.group(2);
        String separator = query.isEmpty() ? "?" : (query.endsWith("&") ? "" : "&");
        if (query.contains("allowMultiQueries")) {
            return matcher.group(1) + "/" + schema + query;
        }
        return matcher.group(1) + "/" + schema + query + separator + "allowMultiQueries=true";
    }

    private static String serverUrl() {
        Matcher matcher = JDBC_BASE.matcher(BASE_URL);
        assertTrue(matcher.matches(),
                "FLYWAY_ALL_IT_URL 必须形如 jdbc:mysql://host:port/db?params：" + BASE_URL);
        return matcher.group(1) + "/" + (matcher.group(2) == null ? "" : matcher.group(2));
    }

    private static int failedHistoryRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")) {
            assertTrue(resultSet.next(), "查询应返回一行");
            return resultSet.getInt(1);
        }
    }

    private static int baselineRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE' AND success = 1")) {
            assertTrue(resultSet.next(), "查询应返回一行");
            return resultSet.getInt(1);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1")) {
            assertTrue(resultSet.next(), "查询应返回一行");
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
