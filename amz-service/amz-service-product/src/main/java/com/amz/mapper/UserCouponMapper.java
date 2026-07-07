package com.amz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.amz.model.pojo.Coupon;
import com.amz.model.pojo.UserCoupon;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {
    List<Coupon> getCouponsByUserId(Integer userId);
}
