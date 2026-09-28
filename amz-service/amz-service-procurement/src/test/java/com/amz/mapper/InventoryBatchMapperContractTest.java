package com.amz.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryBatchMapperContractTest {

    @Test
    @DisplayName("批次成本聚合 SQL 必须只算 ACTIVE，并保持 total_cost 优先的成本口径")
    void batchCostAggregationKeepsActiveScopeAndCostConvention() throws Exception {
        Method method = InventoryBatchMapper.class.getMethod(
                "sumActiveBatchCost", Long.class, String.class);
        Select select = method.getAnnotation(Select.class);

        assertNotNull(select, "批次成本聚合必须使用显式 SQL，避免 selectList 把全部行拉回内存");
        String sql = String.join(" ", select.value()).toUpperCase();
        assertTrue(sql.contains("SUM("), "必须由数据库完成聚合：" + sql);
        assertTrue(sql.contains("STATUS = 'ACTIVE'"), "成本口径必须限定 ACTIVE：" + sql);
        assertTrue(sql.contains("TOTAL_COST"), "总成本必须优先读取 total_cost：" + sql);
        assertTrue(sql.contains("UNIT_COST") && sql.contains("QUANTITY"),
                "total_cost 为空时必须保留原 unit_cost × quantity 口径：" + sql);
    }

    @Test
    @DisplayName("库存批次扣减 SQL 必须保留原子守卫，并先判断状态再执行相对扣减")
    void atomicDeductionSqlKeepsGuardAndEvaluationOrder() throws Exception {
        Method method = InventoryBatchMapper.class.getMethod(
                "decreaseAvailableQuantityAtomic", Long.class, Long.class, String.class, Integer.class);
        Update update = method.getAnnotation(Update.class);

        assertNotNull(update, "原子扣减必须使用 @Update");
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ");

        assertTrue(sql.contains("UPDATE amz_inventory_batch"));
        assertTrue(sql.contains("available_quantity = available_quantity - #{qty}"));
        assertTrue(sql.contains("shop_id = #{shopId}"));
        assertTrue(sql.contains("sku = #{sku}"));
        assertTrue(sql.contains("status = 'ACTIVE'"));
        assertTrue(sql.contains("available_quantity >= #{qty}"));

        int statusIndex = sql.indexOf("status = CASE");
        int deductIndex = sql.indexOf("available_quantity = available_quantity - #{qty}");
        assertTrue(statusIndex >= 0, "必须将扣至 0 的批次置为 DEPLETED");
        assertTrue(deductIndex >= 0, "必须使用相对扣减");
        assertTrue(statusIndex < deductIndex,
                "MySQL 单表 UPDATE 从左到右求值，status 必须在 available_quantity 之前计算");
    }
}