package com.amz.mapper;

import com.amz.model.InventorySyncLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步日志 Mapper。
 */
@Mapper
public interface InventorySyncLogMapper extends BaseMapper<InventorySyncLog> {
}
