package com.amz.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护 docker/init-sql-legacy 的「只归档、不执行」不变式。
 *
 * <p>实测基线（MySQL 8.0.46，31 个脚本各自单独执行；证据见
 * docs/superpowers/evidence/2026-09-30-p1-3-risk4-init-sql-legacy.md）：
 *
 * <ul>
 *   <li>14-init-tables-ops.sql：amz_keyword_rank 用了列名 rank，MySQL 8.0 起 RANK 是保留字 → ERROR 1064。</li>
 *   <li>19 / 23：MariaDB 专有语法 ALTER TABLE ... ADD COLUMN IF NOT EXISTS → ERROR 1064。</li>
 *   <li>28~33：脚本内没有 USE 语句，直接执行 → ERROR 1046 No database selected。</li>
 * </ul>
 *
 * <p>共 9/31 不可执行。只要其中任何一个进入初始化路径，MySQL 容器初始化就会失败，
 * 因此这里把「已知不可执行集合」冻结下来：新增同类缺陷会立刻失败，修复后必须回来更新基线。
 */
class LegacyInitSqlArchiveContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Path ARCHIVE_DIR = ROOT.resolve("docker/init-sql-legacy");

    private static final Pattern CONDITIONAL_ALTER_DDL = Pattern.compile(
            "(?i)\\b(ADD\\s+(?:COLUMN|INDEX|UNIQUE|KEY|CONSTRAINT|PRIMARY\\s+KEY|FOREIGN\\s+KEY)\\s+IF\\s+NOT\\s+EXISTS"
                    + "|MODIFY\\s+(?:COLUMN\\s+)?IF\\s+NOT\\s+EXISTS"
                    + "|CHANGE\\s+(?:COLUMN\\s+)?IF\\s+NOT\\s+EXISTS"
                    + "|DROP\\s+(?:COLUMN|INDEX|PRIMARY\\s+KEY|FOREIGN\\s+KEY|CONSTRAINT)\\s+IF\\s+EXISTS"
                    + "|CREATE\\s+INDEX\\s+IF\\s+NOT\\s+EXISTS)");

    private static final Pattern BARE_RANK_COLUMN = Pattern.compile("(?i)^\\s*`?rank`?\\s+\\w+");

    private static final Pattern USE_DATABASE = Pattern.compile("(?i)^\\s*USE\\s+[a-z0-9_]+\\s*;");

    private static final Set<String> KNOWN_CONDITIONAL_DDL = Set.of(
            "19-init-tables-field-permission.sql",
            "23-init-tables-procurement-upgrade.sql");

    private static final Set<String> KNOWN_RESERVED_WORD_COLUMN = Set.of("14-init-tables-ops.sql");

    private static final Set<String> KNOWN_WITHOUT_USE = Set.of(
            "28-init-tables-p1-listing-monitor.sql",
            "29-init-tables-p1-order-audit.sql",
            "30-init-tables-p1-realtime-profit.sql",
            "31-init-tables-p1-multi-warehouse.sql",
            "32-init-tables-p2-multiplatform.sql",
            "33-init-tables-p2-ai-tools.sql");

    @Test
    void noDeploymentDescriptorReferencesTheArchive() throws IOException {
        for (Path descriptor : deploymentDescriptors()) {
            assertFalse(read(descriptor).contains("init-sql-legacy"),
                    descriptor + " 不得引用 docker/init-sql-legacy：该目录 9/31 脚本在 MySQL 8.0 上执行失败");
        }
    }

    @Test
    void incompatibleScriptsAreFrozenAndMarkedArchiveOnly() throws IOException {
        assertEquals(KNOWN_CONDITIONAL_DDL, filesMatching(CONDITIONAL_ALTER_DDL),
                "MariaDB 条件 DDL 集合发生变化：新增必须同步评估 MySQL 8 兼容性，修复后请回来更新基线");
        assertEquals(KNOWN_RESERVED_WORD_COLUMN, filesMatching(BARE_RANK_COLUMN),
                "裸 rank 列名集合发生变化：MySQL 8.0 起 RANK 是保留字，禁止再引入");

        Set<String> mustBeMarked = new TreeSet<>(KNOWN_CONDITIONAL_DDL);
        mustBeMarked.addAll(KNOWN_RESERVED_WORD_COLUMN);
        for (String name : mustBeMarked) {
            assertTrue(read(ARCHIVE_DIR.resolve(name)).contains("[ARCHIVE ONLY]"),
                    name + " 在 MySQL 8.0 上不可执行，必须带 [ARCHIVE ONLY] 标记，防止被挪回初始化路径");
        }
    }

    @Test
    void scriptsWithoutUseStatementAreFrozen() throws IOException {
        assertEquals(KNOWN_WITHOUT_USE, filesMissing(USE_DATABASE),
                "缺少 USE 语句的脚本集合发生变化：这类脚本一旦被挂载会直接 ERROR 1046，不能静默新增");
    }

    private static List<Path> deploymentDescriptors() throws IOException {
        List<Path> descriptors = new ArrayList<>();
        for (String relative : List.of("docker-compose.yml", "Dockerfile", "amz-frontend/Dockerfile")) {
            Path candidate = ROOT.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                descriptors.add(candidate);
            }
        }
        for (String directory : List.of("k8s", ".github")) {
            Path base = ROOT.resolve(directory);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile).forEach(descriptors::add);
            }
        }
        assertFalse(descriptors.isEmpty(), "必须至少扫描到一个部署描述符，否则这条门禁是空的");
        return descriptors;
    }

    private static List<Path> archiveSqlFiles() throws IOException {
        assertTrue(Files.isDirectory(ARCHIVE_DIR), "缺少归档目录：" + ARCHIVE_DIR);
        try (Stream<Path> files = Files.list(ARCHIVE_DIR)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    private static Set<String> filesMatching(Pattern pattern) throws IOException {
        Set<String> hits = new TreeSet<>();
        for (Path file : archiveSqlFiles()) {
            for (String line : codeLines(file)) {
                if (pattern.matcher(line).find()) {
                    hits.add(file.getFileName().toString());
                    break;
                }
            }
        }
        return hits;
    }

    private static Set<String> filesMissing(Pattern pattern) throws IOException {
        Set<String> missing = new TreeSet<>();
        for (Path file : archiveSqlFiles()) {
            boolean found = false;
            for (String line : codeLines(file)) {
                if (pattern.matcher(line).find()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(file.getFileName().toString());
            }
        }
        return missing;
    }

    private static List<String> codeLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : read(file).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
                continue;
            }
            lines.add(line);
        }
        return lines;
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
