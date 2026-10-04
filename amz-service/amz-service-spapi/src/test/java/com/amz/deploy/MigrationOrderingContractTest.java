package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移执行顺序的无数据库契约测试。
 * <p>
 * 动因（2026-10-04 一次性 MySQL 实测）：CI 的 test 作业自加入 {@code V10__replenishment_ml_columns.sql}
 * 起连续红，报的是 {@code Table 'amz_spapi_bsit.amz_replenishment_suggestion' doesn't exist}。
 * 真机跑下来 <b>Flyway 那条路（AllModulesFlywayMySqlIT）是全绿的</b> —— 红的是
 * {@code BareSqlBuiltSchemaFlywayStartIT} 自己：它用 {@code Files.list(...).sorted()}
 * 按<b>字典序</b>重放迁移，{@code V10__} 排在 {@code V1__}/{@code V2__} 之前
 * （'V1' 之后是 '_'=0x5F，'V10' 之后是 '0'=0x30），于是 V10 的 ALTER 早于建表。
 * <p>
 * 这个 bug 单看迁移文件永远看不出来：V10 的 SQL 是对的，Flyway 的排序也是对的，
 * 只有「第二个实现」错了。所以这里钉两件事：
 * <ol>
 *   <li>IT 的执行顺序 helper 必须数值序，并且把「字典序会给出错误答案」写成断言（防自证空转）；</li>
 *   <li>按执行顺序走一遍每个模块的迁移，任何 ALTER 引用的表都必须已被前面的文件 CREATE 过。</li>
 * </ol>
 */
@DisplayName("迁移执行顺序：数值序，且 ALTER 不得早于 CREATE")
class MigrationOrderingContractTest {

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.*\\.sql$");
    /**
     * CREATE / ALTER 一把抓：**按文件里出现的先后顺序**判定。
     * 分成两个正则各扫一遍会误报 —— V2 自己就是先 CREATE 再 ALTER 同一张表
     * （建表后立刻补索引/列），先扫 ALTER 就看不到同文件前面的 CREATE。
     * 限定名 `db`.`tbl` 取最后一段，不然会把库名当表名。
     */
    private static final Pattern CREATE_OR_ALTER = Pattern.compile(
            "(?i)\\b(CREATE\\s+TABLE|ALTER\\s+TABLE)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:`?\\w+`?\\.)?`?(\\w+)`?");

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("amz-service"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "无法从 user.dir 定位仓库根：" + System.getProperty("user.dir"));
        return dir;
    }

    private static List<Path> migrationFiles(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            for (Path p : list.toList()) {
                if (MIGRATION_FILE.matcher(p.getFileName().toString()).matches()) {
                    files.add(p);
                }
            }
            return files;
        }
    }

    @Test
    @DisplayName("V10 必须排在 V2 之后，而字典序会把顺序排错（这条断言同时钉住两种排法）")
    void versionOrderIsNumericNotLexicographic() {
        List<Path> files = List.of(
                Path.of("V10__later.sql"), Path.of("V1__init.sql"),
                Path.of("V2__x.sql"), Path.of("V9__y.sql"));

        List<String> numeric = BareSqlBuiltSchemaFlywayStartIT.sortedByMigrationVersion(files).stream()
                .map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("V1__init.sql", "V2__x.sql", "V9__y.sql", "V10__later.sql"), numeric);

        List<String> lexicographic = files.stream()
                .map(p -> p.getFileName().toString()).sorted(Comparator.naturalOrder()).toList();
        assertEquals("V10__later.sql", lexicographic.get(0),
                "字典序的第一位就是 V10 —— 这正是 CI 红的那条路的排法，不能让它成为执行顺序");
        assertFalse(numeric.equals(lexicographic),
                "两种排法必须不同，否则这条断言什么都没钉住");
    }

    @Test
    @DisplayName("按执行顺序走一遍：任何 ALTER 引用的表都必须先被 CREATE")
    void everyAlterTargetIsCreatedEarlierInExecutionOrder() throws IOException {
        List<String> violations = new ArrayList<>();
        int modules = 0;
        int filesScanned = 0;
        try (Stream<Path> dirs = Files.list(repoRoot().resolve("amz-service"))) {
            for (Path module : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path migrationDir = module.resolve("src/main/resources/db/migration");
                if (!Files.isDirectory(migrationDir)) {
                    continue;
                }
                modules++;
                List<Path> files = BareSqlBuiltSchemaFlywayStartIT
                        .sortedByMigrationVersion(migrationFiles(migrationDir));
                Set<String> created = new HashSet<>();
                for (Path file : files) {
                    filesScanned++;
                    String sql = Files.readString(file, StandardCharsets.UTF_8);
                    // 注释里的 ALTER/CREATE 不算语句（迁移里常用注释记录历史）
                    String body = sql.replaceAll("(?m)^\\s*--.*$", "").replaceAll("/\\*.*?\\*/", "");
                    for (Matcher m = CREATE_OR_ALTER.matcher(body); m.find(); ) {
                        String table = m.group(2).toLowerCase();
                        if (m.group(1).toUpperCase().startsWith("ALTER")) {
                            if (!created.contains(table)) {
                                violations.add(module.getFileName() + " / " + file.getFileName()
                                        + " ALTER " + table + " 早于任何 CREATE");
                            }
                        } else {
                            created.add(table);
                        }
                    }
                }
            }
        }
        assertTrue(modules >= 10, "发现的迁移模块太少（" + modules + "），清点分母不可信");
        assertTrue(filesScanned >= 40, "扫描的迁移文件太少（" + filesScanned + "），清点分母不可信");
        assertTrue(violations.isEmpty(),
                "裸 SQL 按执行顺序重放会失败：\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("每个模块的版本号在执行顺序里严格递增且不重复（baseline 取最后一个文件）")
    void versionsAreUniqueAndStrictlyIncreasing() throws IOException {
        int modules = 0;
        try (Stream<Path> dirs = Files.list(repoRoot().resolve("amz-service"))) {
            for (Path module : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path migrationDir = module.resolve("src/main/resources/db/migration");
                if (!Files.isDirectory(migrationDir)) {
                    continue;
                }
                modules++;
                List<Path> files = BareSqlBuiltSchemaFlywayStartIT
                        .sortedByMigrationVersion(migrationFiles(migrationDir));
                List<Integer> versions = files.stream()
                        .map(BareSqlBuiltSchemaFlywayStartIT::migrationVersion).toList();
                assertEquals(versions.size(), new HashSet<>(versions).size(),
                        module.getFileName() + " 有重复版本号：" + versions);
                for (int i = 1; i < versions.size(); i++) {
                    assertTrue(versions.get(i) > versions.get(i - 1),
                            module.getFileName() + " 执行顺序里版本没有递增：" + versions);
                }
                assertEquals(1, versions.get(0),
                        module.getFileName() + " 的首个迁移应是 V1（baseline 约定）：" + versions);
            }
        }
        assertTrue(modules >= 10, "发现的迁移模块太少（" + modules + "），分母不可信");
    }
}
