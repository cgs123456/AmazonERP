package com.amz.mapper;

import com.amz.model.InventoryBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryBatchMapper extends BaseMapper<InventoryBatch> {

    /**
     * 原子扣减批次可用库存。
     * <p>
     * 只有批次仍为 ACTIVE、归属店铺/SKU 匹配且可用量充足时才成功。
     * 返回 1 表示扣减成功，返回 0 表示库存不足或并发状态已变化，调用方必须整体失败。
     * <p>
     * MySQL 单表 UPDATE 的赋值从左到右求值，因此 status 必须在 available_quantity 之前计算，
     * 否则 CASE 中的 available_quantity 会读取已扣减后的值并发生二次扣减。
     */
    @Update("UPDATE amz_inventory_batch " +
            "SET status = CASE WHEN available_quantity = #{qty} THEN 'DEPLETED' ELSE status END, " +
            "available_quantity = available_quantity - #{qty}, update_time = NOW() " +
            "WHERE id = #{id} AND shop_id = #{shopId} AND sku = #{sku} " +
            "AND status = 'ACTIVE' AND available_quantity >= #{qty}")
    int decreaseAvailableQuantityAtomic(@Param("id") Long id,
                                        @Param("shopId") Long shopId,
                                        @Param("sku") String sku,
                                        @Param("qty") Integer qty);

    /**
     * 重新激活已扣空的批次（退货/FIFO 回补时调用）。
     * <p>
     * 与 {@link #decreaseAvailableQuantityAtomic} 对称，但<b>赋值顺序相反</b>：
     * 扣减时 {@code status} 必须在 {@code available_quantity} 之前（否则 CASE 会读到已扣减的值）；
     * 回补时先恢复状态再加量，且刻意拆成两条语句——把「是否仍为 DEPLETED」的判断
     * 与「加量」分开，避免 MySQL 从左到右求值把 CASE 读到刚加过的值。
     * <p>
     * 只有批次归属店铺/SKU 匹配时才回补（防止把退货补进他店批次）。
     */
    @Update("UPDATE amz_inventory_batch " +
            "SET status = CASE WHEN status = 'DEPLETED' THEN 'ACTIVE' ELSE status END, " +
            "update_time = NOW() " +
            "WHERE id = #{id} AND shop_id = #{shopId} AND sku = #{sku}")
    int reactivateBatch(@Param("id") Long id,
                        @Param("shopId") Long shopId,
                        @Param("sku") String sku);

    /**
     * 原子增加批次可用库存（与 {@link #reactivateBatch} 配合：先激活再加量）。
     */
    @Update("UPDATE amz_inventory_batch " +
            "SET available_quantity = available_quantity + #{qty}, update_time = NOW() " +
            "WHERE id = #{id} AND shop_id = #{shopId} AND sku = #{sku}")
    int increaseAvailableQuantityAtomic(@Param("id") Long id,
                                        @Param("shopId") Long shopId,
                                        @Param("sku") String sku,
                                        @Param("qty") Integer qty);

    /**
     * 按店铺和 SKU 聚合 ACTIVE 批次成本。
     * <p>数据库负责 SUM/COUNT，调用方不得先拉取全部批次行再在 JVM 汇总。</p>
     */
    @Select("SELECT COUNT(*) AS batchCount, " +
            "COALESCE(SUM(quantity), 0) AS totalQuantity, " +
            "SUM(COALESCE(total_cost, " +
            "unit_cost * quantity + COALESCE(freight_cost, 0) + " +
            "COALESCE(customs_cost, 0) + COALESCE(other_cost, 0))) AS totalBatchCost " +
            "FROM amz_inventory_batch " +
            "WHERE shop_id = #{shopId} AND sku = #{sku} AND status = 'ACTIVE'")
    com.amz.dto.BatchCostSummary sumActiveBatchCost(@Param("shopId") Long shopId,
                                                    @Param("sku") String sku);}
