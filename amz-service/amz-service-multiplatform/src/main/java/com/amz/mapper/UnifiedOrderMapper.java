package com.amz.mapper;

import com.amz.model.UnifiedOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 多平台统一订单 Mapper。
 */
@Mapper
public interface UnifiedOrderMapper extends BaseMapper<UnifiedOrder> {
}
