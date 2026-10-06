package com.amz.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护 amz-service-order V4 的不可重入 DROP INDEX：不修它，但把它冻住。
 *
 * <p>实测基线（MySQL 8.0.46，证据见
 * docs/superpowers/evidence/2026-09-30-p1-3-risk2-order-v4-drop-index.md）：
 *
 * <ul>
 *   <li>V1-V3 之后首次执行 V4：rc=0。</li>
 *   <li>重跑 V4：ERROR 1091 Can't DROP 'uk_amazon_order' —— 不可重入坐实。</li>
 *   <li>旧索引在、新唯一键也在的半迁移状态：ERROR 1061；MySQL 8 的原子 DDL 把两个 DROP
 *       一起回滚，旧索引仍在，修掉冲突后重跑 rc=0。</li>
 * </ul>
 *
 * <p>为什么不改成可重入：MySQL 8 没有 DROP INDEX IF EXISTS；改 V4 文件本身会改变 Flyway
 * checksum，已迁移库下次启动直接 Migration checksum mismatch 拒绝启动；而新增一条更高版本的
 * 迁移又排在 V4 之后，V4 失败时根本跑不到。所以这里的选择是「冻住 + 手册」，
 * 处置流程见 docs/superpowers/runbooks/order-v4-drop-index-repair.md。
 */
class OrderV4IndexMigrationContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Path MODULES_DIR = ROOT.resolve("amz-service");
    private static final Path V4 = MODULES_DIR.resolve(
            "amz-service-order/src/main/resources/db/migration/V4__order_shop_scoped_identity.sql");
    private static final Path REPAIR_RUNBOOK =
            ROOT.resolve("docs/superpowers/runbooks/order-v4-drop-index-repair.md");

    private static final Pattern DROP_INDEX = Pattern.compile("(?i)\\bDROP\\s+(?:INDEX|KEY)\\b");

    private static final Set<String> KNOWN_DROP_INDEX_MIGRATIONS = Set.of(
            "amz-service-order/V4__order_shop_scoped_identity.sql",
            // ad V8 DROP idx_shop（uk_campaign 左前缀覆盖的冗余索引）：已按本测试要求的流程审过——
            // 该索引自 V1 存在、DROP 无任何条件守卫、且排在全部 MODIFY 之后，
            // 半应用状态只会停在 MODIFY（放宽宽度，可幂等重跑），不存在 1091 重试死锁面。
            "amz-service-ad/V8__campaign_id_width_convergence.sql");

    @Test
    void onlyOrderV4DropsIndexes() throws IOException {
        assertEquals(KNOWN_DROP_INDEX_MIGRATIONS, migrationsDroppingIndexes(),
                "出现新的 DROP INDEX 迁移：MySQL 8 没有 DROP INDEX IF EXISTS，这类语句不可重入。"
                        + "新增前请先读 docs/superpowers/runbooks/order-v4-drop-index-repair.md，"
                        + "确认失败后如何处置，再回来更新这里冻结的集合");
    }

    @Test
    void v4DropsLegacyIndexesWithoutGuards() throws IOException {
        assertTrue(Files.isRegularFile(V4), "V4 迁移文件必须存在：" + V4);
        String sql = read(V4);
        assertTrue(sql.contains("DROP INDEX uk_amazon_order"),
                "V4 必须回收旧的全局唯一键 uk_amazon_order");
        assertTrue(sql.contains("DROP INDEX idx_shop"),
                "V4 必须回收旧的 idx_shop 并替换为 idx_shop_purchase_date");
        assertTrue(sql.contains("ADD UNIQUE KEY uk_shop_market_order"),
                "V4 必须建立店铺+站点+订单号的复合唯一键");
        assertTrue(sql.contains("ADD INDEX idx_shop_purchase_date"),
                "V4 必须建立 idx_shop_purchase_date");
        assertFalse(DROP_INDEX.matcher(sql).find() && sql.contains("IF EXISTS"),
                "MySQL 8 不支持 DROP INDEX IF EXISTS：写成 IF EXISTS 只会让人误以为这条迁移可重入，"
                        + "实际上语法层面就过不去");
    }

    @Test
    void repairRunbookDocumentsTheFailureMode() throws IOException {
        assertTrue(Files.isRegularFile(REPAIR_RUNBOOK),
                "V4 不可重入，必须有可执行的修复手册：" + REPAIR_RUNBOOK);
        String runbook = read(REPAIR_RUNBOOK);
        assertTrue(runbook.contains("1091"),
                "手册必须写明 ERROR 1091 这个失败码，否则排障时无法检索到");
        assertTrue(runbook.contains("uk_amazon_order") && runbook.contains("idx_shop"),
                "手册必须写明两个被 DROP 的旧索引名，否则无法按现状判断库处于哪种状态");
    }

    private static Set<String> migrationsDroppingIndexes() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> modules = Files.list(MODULES_DIR)) {
            List<Path> migrationDirs = modules
                    .filter(Files::isDirectory)
                    .map(module -> module.resolve("src/main/resources/db/migration"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
            for (Path dir : migrationDirs) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path path : files.filter(
                            candidate -> candidate.getFileName().toString().endsWith(".sql")).toList()) {
                        if (DROP_INDEX.matcher(read(path)).find()) {
                            found.add(relativize(path));
                        }
                    }
                }
            }
        }
        return found;
    }

    private static String relativize(Path migration) {
        Path relative = MODULES_DIR.relativize(migration);
        String module = relative.getName(0).toString();
        return module + "/" + migration.getFileName();
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