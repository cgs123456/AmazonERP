package com.amz.mapper;

import com.amz.model.ListingCopyTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Listing 跨站点复制任务 Mapper。
 */
@Mapper
public interface ListingCopyTaskMapper extends BaseMapper<ListingCopyTask> {
}
