package com.amz.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 店铺级角色默认值迁移（V6）契约。
 * <p>
 * 存在动因见 HANDOFF §S22：{@code amz_user_shop.role} 的默认值是 OPERATOR，
 * 比 {@code amz_user.role} 的 VIEWER 更宽。在店铺角色接通之前这不构成问题
 * （那张表的 role 只写不读），接通之后「插入绑定时不显式给 role」第一次有了安全后果。
 */
@DisplayName("店铺级角色默认值迁移契约")
class ShopRoleDefaultMigrationContractTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V6__shop_role_default_viewer.sql");

    @Test
    @DisplayName("V6 把 amz_user_shop.role 的默认值收敛到 VIEWER")
    void v6NarrowsShopRoleDefaultToViewer() throws Exception {
        assertTrue(Files.exists(MIGRATION), "缺少店铺角色默认值迁移 V6");

        String sql = readNormalized();

        assertTrue(sql.contains("alter table amz_user_shop"),
                "迁移必须作用于 amz_user_shop");
        assertTrue(sql.contains("modify column role varchar(20) default 'viewer'"),
                "必须把默认值改成 VIEWER（最小权限），实际内容：" + sql);
    }

    @Test
    @DisplayName("V6 只改默认值，不改存量数据（授权决策不替管理员做）")
    void v6DoesNotRewriteExistingRows() throws Exception {
        String sql = readNormalized();

        // 批量 UPDATE 会替管理员决定存量配置；那里可能有他有意设的 OPERATOR。
        assertFalse(sql.contains("update amz_user_shop"),
                "迁移不得改写存量行的角色，只改默认值");
        assertFalse(sql.contains("delete "),
                "迁移不得删除任何行");
    }

    @Test
    @DisplayName("V6 不得改动列类型或长度（避免触发全表重建）")
    void v6DoesNotChangeColumnType() throws Exception {
        String sql = readNormalized();

        // 与 V1 建表一致：VARCHAR(20)，只换默认值
        assertFalse(sql.contains("varchar(50)"),
                "不得把 role 改成 VARCHAR(50)：V1 是 VARCHAR(20)，改宽会重建表");
    }

    private static String readNormalized() throws Exception {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
