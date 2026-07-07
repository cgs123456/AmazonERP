package com.amz.mapper;

import com.amz.model.AmzProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Amazon 商品主数据 Mapper。
 */
@Mapper
public interface AmzProductMapper extends BaseMapper<AmzProduct> {
}
