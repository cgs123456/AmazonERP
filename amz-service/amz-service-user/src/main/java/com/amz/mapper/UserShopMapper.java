package com.amz.mapper;

import com.amz.model.pojo.UserShop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-店铺关联 Mapper（多店铺授权查询）
 */
@Mapper
public interface UserShopMapper extends BaseMapper<UserShop> {
}
