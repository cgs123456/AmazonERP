package com.amz.mapper;

import com.amz.model.FbaInventory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * FBA 库存 Mapper。
 */
@Mapper
public interface FbaInventoryMapper extends BaseMapper<FbaInventory> {
}
