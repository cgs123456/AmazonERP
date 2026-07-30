package com.amz.mapper;

import com.amz.model.WarehouseInventory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WarehouseInventoryMapper extends BaseMapper<WarehouseInventory> {

    /**
     * 原子扣减库存：仅当可用库存（quantity - reserved_quantity）&gt;= qty 时扣减成功。
     * <p>
     * 高并发下用 SQL 行级锁保证不超卖，返回受影响行数：
     * <ul>
     *   <li>0 表示可用库存不足（调用方应抛业务异常）</li>
     *   <li>1 表示扣减成功</li>
     * </ul>
     */
    @Update("UPDATE amz_warehouse_inventory SET quantity = quantity - #{qty}, update_time = NOW() " +
            "WHERE id = #{id} AND (quantity - IFNULL(reserved_quantity, 0)) >= #{qty}")
    int decreaseQuantityAtomic(@Param("id") Long id, @Param("qty") Integer qty);

    /**
     * 原子增加库存。
     */
    @Update("UPDATE amz_warehouse_inventory SET quantity = quantity + #{qty}, update_time = NOW() " +
            "WHERE id = #{id}")
    int increaseQuantityAtomic(@Param("id") Long id, @Param("qty") Integer qty);
}
