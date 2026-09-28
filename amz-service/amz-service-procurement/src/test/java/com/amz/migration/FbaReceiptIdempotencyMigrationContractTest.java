package com.amz.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FBA 签收幂等数据库迁移契约")
class FbaReceiptIdempotencyMigrationContractTest {

    @Test
    @DisplayName("V2 为库存批次增加货件明细唯一幂等键")
    void v2AddsShipmentItemUniqueKey() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V2__fba_receipt_idempotency.sql");
        assertTrue(Files.exists(migration), "缺少 FBA 签收幂等迁移 V2");

        String sql = Files.readString(migration, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        assertTrue(sql.contains("alter table amz_inventory_batch"),
                "迁移必须修改库存批次表");
        assertTrue(sql.contains("add column shipment_item_id bigint"),
                "迁移必须增加 shipment_item_id 列");
        assertTrue(sql.contains("unique key uk_inventory_batch_shipment_item (shipment_item_id)"),
                "迁移必须为 shipment_item_id 建立唯一键");
    }
}