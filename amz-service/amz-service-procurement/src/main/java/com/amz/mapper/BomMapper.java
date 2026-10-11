package com.amz.mapper;

import com.amz.model.Bom;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 物料清单（BOM）Mapper。
 */
@Mapper
public interface BomMapper extends BaseMapper<Bom> {
}
