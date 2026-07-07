package com.amz.mapper;

import com.amz.model.ProductSalesStats;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品销量统计 Mapper。
 */
@Mapper
public interface ProductSalesStatsMapper extends BaseMapper<ProductSalesStats> {
}
