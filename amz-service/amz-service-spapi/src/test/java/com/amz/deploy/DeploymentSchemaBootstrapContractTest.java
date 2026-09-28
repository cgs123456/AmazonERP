package com.amz.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 守护数据库 schema 引导：Compose / k8s 只建空库，Flyway 是唯一建表入口。
 */
class DeploymentSchemaBootstrapContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Map<String, String> DB_MODULES = Map.ofEntries(
            Map.entry("user", "amz_user"),
            Map.entry("product", "amz_product"),
            Map.entry("order", "amz_order"),
            Map.entry("search", "amz_search"),
            Map.entry("spapi", "amz_spapi"),
            Map.entry("ad", "amz_ad"),
            Map.entry("procurement", "amz_procurement"),
            Map.entry("customer", "amz_customer"),
            Map.entry("logistics", "amz_logistics"),
            Map.entry("ops", "amz_ops"),
            Map.entry("finance", "amz_finance"),
            Map.entry("multiplatform", "amz_multiplatform"),
            Map.entry("ai", "amz_ai"),
            Map.entry("report", "amz_report")
    );
    private static final Set<String> EXPECTED_DBS = Set.copyOf(DB_MODULES.values());
    private static final Pattern CREATE_DATABASE = Pattern.compile(
            "(?i)CREATE\\s+DATABASE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)");
    private static final Pattern SCHEMA_NAME = Pattern.compile(
            "schema_name='([a-z0-9_]+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_OR_DATA_DDL = Pattern.compile(
            "(?i)\\b(CREATE\\s+TABLE|INSERT\\s+INTO|UPDATE\\s+|DELETE\\s+FROM|ALTER\\s+TABLE|DROP\\s+TABLE)\\b");
    private static final Pattern INVALID_COMPOSE_DEFAULT = Pattern.compile(
            "\\$\\{[A-Z][A-Z0-9_]*:\\}");

    @Test
    void composeInitDirectoryContainsOnlySchemaBootstrap() throws IOException {
        Path initDir = ROOT.resolve("docker/init-sql");
        List<String> names;
        try (Stream<Path> files = Files.list(initDir)) {
            names = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("01-init-databases.sql"), names,
                "docker/init-sql 只能保留唯一建库入口，不能再次挂载表结构");

        String sql = read(initDir.resolve("01-init-databases.sql"));
        assertEquals(EXPECTED_DBS, createDatabases(sql), "Compose 建库集合必须恰好是 14 个");
        assertSchemaOnly(sql);
        assertFalse(Files.exists(ROOT.resolve("init_all_tables.sql")),
                "仓库根不得保留可执行的 init_all_tables.sql");
    }

    @Test
    void composeMountsOnlySchemaBootstrap() throws IOException {
        String compose = read(ROOT.resolve("docker-compose.yml"));
        assertTrue(compose.contains(
                        "- ./docker/init-sql/01-init-databases.sql:/docker-entrypoint-initdb.d/01-init-databases.sql:ro"),
                "Compose 只能只读挂载唯一建库脚本，不能整目录挂载");
        assertFalse(compose.contains("init-sql-legacy:/docker-entrypoint-initdb.d"),
                "Compose 不得挂载 legacy 表结构目录");
    }

    @Test
    void composeFailsClosedWhenRequiredSecretsAreMissing() throws IOException {
        String compose = read(ROOT.resolve("docker-compose.yml"));
        Matcher invalid = INVALID_COMPOSE_DEFAULT.matcher(compose);
        assertFalse(invalid.find(),
                "Compose 不得使用会被 docker compose config 拒绝的 ${VAR:} 语法");
        assertTrue(compose.contains("MYSQL_ROOT_PASSWORD=${DB_PASSWORD:?DB_PASSWORD_required}"),
                "MySQL root 密码必须缺失即失败，不能默认空密码启动");
        for (String variable : List.of("DB_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_PASSWORD",
                "JWT_SECRET_KEY", "AMZ_CRYPTO_KEY", "MONGO_PASSWORD", "GRAFANA_PASSWORD")) {
            assertFalse(compose.contains("${" + variable + ":-"),
                    variable + " 不得使用空默认值绕过生产凭据门禁");
            assertTrue(compose.contains("${" + variable + ":?"),
                    variable + " 必须使用 Compose 必填变量语法");
        }
    }

    @Test
    void kubernetesJobCreatesExactlyTheSameSchemasBeforeServices() throws IOException {
        String job = read(ROOT.resolve("k8s/infra/mysql-init-job.yaml"));
        assertEquals(EXPECTED_DBS, createDatabases(job),
                "k8s Job 建库集合必须与 Compose 完全一致");
        assertSchemaOnly(job);
        assertTrue(job.contains("kind: Job"), "必须使用 batch/v1 Job 执行一次性建库");
        assertTrue(job.contains("restartPolicy: Never"));
        assertTrue(job.contains("backoffLimit:"));
        assertTrue(job.contains("activeDeadlineSeconds:"));
        assertTrue(job.contains("mysqladmin ping"), "Job 必须先等待 MySQL ready");
        assertTrue(job.contains("key: DB_USERNAME"), "Job 必须从 Secret 读取 DB_USERNAME");
        assertTrue(job.contains("key: DB_PASSWORD"), "Job 必须从 Secret 读取 DB_PASSWORD");
    }

    @Test
    void everyDatabaseServiceWaitsForItsOwnSchema() throws IOException {
        for (Map.Entry<String, String> entry : DB_MODULES.entrySet()) {
            Path deployment = ROOT.resolve("k8s/services/amz-service-" + entry.getKey() + ".yaml");
            assertTrue(Files.isRegularFile(deployment), "缺少 Deployment 清单：" + deployment);
            String yaml = read(deployment);
            assertTrue(yaml.contains("name: wait-for-schema"),
                    entry.getKey() + " 缺少 schema 等待 initContainer");
            assertTrue(yaml.contains("mysqladmin ping"),
                    entry.getKey() + " 的 initContainer 必须先等待 MySQL ready");

            Matcher matcher = SCHEMA_NAME.matcher(yaml);
            List<String> schemas = matcher.results().map(result -> result.group(1)).toList();
            assertEquals(List.of(entry.getValue()), schemas,
                    entry.getKey() + " 只能等待自己的 schema，不能串库或漏库");
        }
    }

    private static Set<String> createDatabases(String text) {
        Matcher matcher = CREATE_DATABASE.matcher(text);
        return matcher.results().map(result -> result.group(1).toLowerCase()).collect(java.util.stream.Collectors.toSet());
    }

    private static void assertSchemaOnly(String text) {
        assertFalse(TABLE_OR_DATA_DDL.matcher(text).find(),
                "schema 引导不得包含表结构或业务数据 DML");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
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
