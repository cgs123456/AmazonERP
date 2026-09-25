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
        assertEquals(106, tableCounts.size(), "Flyway 唯一表集合必须保持 106 张");
        assertEquals(106, tableCounts.values().stream().mapToInt(Integer::intValue).sum(),
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
