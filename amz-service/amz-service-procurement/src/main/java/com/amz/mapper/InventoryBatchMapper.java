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
