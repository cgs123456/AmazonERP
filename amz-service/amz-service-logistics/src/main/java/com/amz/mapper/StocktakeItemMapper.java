package com.amz.mapper;

import com.amz.model.StocktakeItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存盘点明细 Mapper。
 */
@Mapper
public interface StocktakeItemMapper extends BaseMapper<StocktakeItem> {
}
