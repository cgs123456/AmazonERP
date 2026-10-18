package com.amz.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护多平台模块"租户维度唯一键"的形状（§S33.91 / §S33.104 / §S33.108）。
 *
 * <p>为什么需要这个测试：V4 给商品/消息表的唯一键补了 shop_id、V5 给统一订单表补了
 * shop_id，但**当时没有任何测试守住这个形状**。服务层 exist/去重查询带不带 shop_id
 * 有人守（MultiplatformShopScopedSyncTest / MultiplatformOrderDedupShopScopedTest），
 * DDL 这一侧却空着——而缺陷正是"查询与 DDL 同时漏了租户维度"。
 * 只守一侧，将来有人把唯一键改回全局的，代码测试照样全绿。
 *
 * <p>这里不连数据库，只读迁移文件的最终态：唯一键必须含 shop_id。
 * 与 OrderV4IndexMigrationContractTest 同一手法（不修它，但把它钉住）。
 */
class ShopScopedUniqueKeyContractTest {

    private static final Path MIGRATIONS = findRepoRoot().resolve(
            "amz-service/amz-service-multiplatform/src/main/resources/db/migration");

    @Test
    void unifiedOrderUniqueKeyIsShopScoped() throws IOException {
        String all = readAll();
        assertTrue(all.contains("uk_shop_platform_order"),
                "V5 必须建立含 shop_id 的唯一键 uk_shop_platform_order，"
                        + "否则两店共享同一 platform_order_no 时 insert 撞 1062，"
                        + "整个平台的同步会被标为失败（§S33.108 只改代码不改 DDL 等于只做了一半）");

        // 不能断言"全文不含 uk_platform_order 字面量"——它出现在 V1 的建表语句里，
        // 而"旧键已废弃"是 V5 的 DROP 在运行时造成的效果，不是字面量的有无。
        // 要盯的是 V5 确实 DROP 了它：否则只 ADD 新键、不 DROP 旧键，
        // 旧键仍在拦，同样会让两店共享订单号时撞键。
        String v5 = read(MIGRATIONS.resolve("V5__unified_order_shop_scoped_key.sql"));
        assertTrue(v5.contains("DROP INDEX uk_platform_order"),
                "V5 必须 DROP 旧的全局唯一键 uk_platform_order，否则旧键仍会拦住跨店同号订单");
        assertTrue(v5.contains("shop_id, platform, platform_order_no"),
                "新唯一键的列顺序应为 (shop_id, platform, platform_order_no)，实际 V5 内容：\n" + v5);
    }

    @Test
    void platformProductAndMessageUniqueKeysAreShopScoped() throws IOException {
        String sql = readAll();
        assertTrue(sql.contains("uk_shop_platform_product"),
                "商品表唯一键 uk_shop_platform_product 必须存在（§S33.91 / §S33.104）");
        assertTrue(sql.contains("uk_shop_platform_msg"),
                "消息表唯一键 uk_shop_platform_msg 必须存在（§S33.91 / §S33.104）");
    }

    /**
     * 三条 ADD UNIQUE KEY 都必须带 shop_id。
     *
     * <p>单独断言"键名存在"是不够的：有人可以把键名留住、把 shop_id 从列里删掉，
     * 三条断言仍全绿，而缺陷原样回来。所以直接盯 ADD UNIQUE KEY 的列清单。
     */
    @Test
    void everyAddedUniqueKeyIncludesShopId() throws IOException {
        for (Path file : sqlFiles()) {
            for (String line : read(file).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.toUpperCase().startsWith("ADD UNIQUE KEY")) {
                    continue;
                }
                assertTrue(trimmed.contains("shop_id"),
                        "新增唯一键必须含 shop_id，否则跨店会互相覆盖或互相判重："
                                + file.getFileName() + " -> " + trimmed);
            }
        }
    }

    private static String readAll() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path file : sqlFiles()) {
            sb.append(read(file)).append("\n");
        }
        return sb.toString();
    }

    private static java.util.List<Path> sqlFiles() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
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
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
