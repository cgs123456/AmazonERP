package com.amz.mapper;

import com.amz.model.Stocktake;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存盘点单 Mapper。
 */
@Mapper
public interface StocktakeMapper extends BaseMapper<Stocktake> {
}
