package com.amz.mapper;

import com.amz.model.pojo.Notification;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知历史 Mapper。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
