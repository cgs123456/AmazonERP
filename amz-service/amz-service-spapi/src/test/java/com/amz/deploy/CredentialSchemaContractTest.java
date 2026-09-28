package com.amz.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证表结构契约：Flyway 是唯一事实源，禁止 classpath 下再保留辅助建表镜像。
 */
class CredentialSchemaContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Path SPAPI_DB_RESOURCES = ROOT.resolve(
            "amz-service/amz-service-spapi/src/main/resources/db");
    private static final Pattern CREATE_CREDENTIAL_TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`?amz_shop_credential`?\\s*\\(");

    @Test
    void credentialTableHasExactlyOneFlywayDefinition() throws IOException {
        Path retiredMirror = SPAPI_DB_RESOURCES.resolve("schema.sql");
        assertFalse(Files.exists(retiredMirror),
                "db/schema.sql 无运行时消费者，必须删除；否则会形成第二份可漂移的建表事实源");

        List<Path> definitions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SPAPI_DB_RESOURCES)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList()) {
                if (CREATE_CREDENTIAL_TABLE.matcher(read(file)).find()) {
                    definitions.add(file);
                }
            }
        }

        assertEquals(1, definitions.size(),
                "amz_shop_credential 必须只有一份 CREATE TABLE，实际：" + definitions);
        assertEquals(SPAPI_DB_RESOURCES.resolve("migration/V2__spapi_call_outbox.sql"),
                definitions.get(0), "唯一建表入口必须是 Flyway V2");
    }

    @Test
    void credentialColumnsMatchProductionEncryptionAndSellerBounds() throws IOException {
        Path migration = SPAPI_DB_RESOURCES.resolve("migration/V2__spapi_call_outbox.sql");
        String sql = read(migration);
        Matcher create = CREATE_CREDENTIAL_TABLE.matcher(sql);
        assertTrue(create.find(), "V2 缺少 amz_shop_credential 建表语句");

        int engine = sql.indexOf(") ENGINE=", create.end());
        assertTrue(engine > create.end(), "无法定位 amz_shop_credential 建表语句结尾");
        String table = sql.substring(create.end(), engine);

        for (String encryptedColumn : List.of(
                "client_secret_encrypted",
                "refresh_token_encrypted",
                "access_key_encrypted",
                "secret_key_encrypted")) {
            assertEquals(2048, varcharLength(table, encryptedColumn),
                    encryptedColumn + " 必须容纳 AES-GCM Base64 扩展后的密文，禁止退回 512");
        }
        assertEquals(64, varcharLength(table, "seller_id"),
                "Amazon Seller ID 的持久化上限必须与迁移一致");
    }

    @Test
    void credentialVersionColumnIsNotNullAndDefaultsToZero() throws IOException {
        Path migration = SPAPI_DB_RESOURCES.resolve("migration/V9__spapi_credential_version.sql");
        String sql = read(migration);

        Matcher matcher = Pattern.compile(
                        "(?is)ALTER\\s+TABLE\\s+`?amz_shop_credential`?.*?"
                                + "ADD\\s+COLUMN\\s+`?version`?\\s+BIGINT\\s+NOT\\s+NULL\\s+DEFAULT\\s+0")
                .matcher(sql);
        assertTrue(matcher.find(),
                "V9 必须为 amz_shop_credential.version 提供 BIGINT NOT NULL DEFAULT 0，否则存量行无法安全参与 CAS");
    }

    @Test
    void noAuxiliaryDdlCanReturnBesideFlywayMigrations() throws IOException {
        try (Stream<Path> files = Files.walk(SPAPI_DB_RESOURCES)) {
            List<Path> auxiliary = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(path -> !path.toString().replace('\\', '/')
                            .contains("/src/main/resources/db/migration/"))
                    .toList();
            assertTrue(auxiliary.isEmpty(),
                    "spapi 的 db/ 根目录不得再放辅助 DDL，只允许 Flyway migration：" + auxiliary);
        }
    }

    private static int varcharLength(String table, String column) {
        Pattern pattern = Pattern.compile("(?is)`?" + Pattern.quote(column)
                + "`?\\s+VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)");
        Matcher matcher = pattern.matcher(table);
        assertTrue(matcher.find(), "缺少 VARCHAR 列定义：" + column);
        return Integer.parseInt(matcher.group(1));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("amz-service/amz-service-spapi"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
