package com.amz.mapper;

import com.amz.model.NotificationInboxEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 入站通知账本 Mapper。 */
@Mapper
public interface NotificationInboxMapper extends BaseMapper<NotificationInboxEntity> {
}