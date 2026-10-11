package com.amz.mapper;

import com.amz.model.InventoryMovement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存流水账 Mapper。
 * <p>
 * 只有 insert 与查询：流水是事实记录，不提供 update/delete 的调用先例。
 */
@Mapper
public interface InventoryMovementMapper extends BaseMapper<InventoryMovement> {
}
