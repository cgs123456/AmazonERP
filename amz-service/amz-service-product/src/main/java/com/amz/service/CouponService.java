package com.amz.service;

import com.amz.model.pojo.Coupon;
import com.amz.model.vo.CouponVo;
import com.amz.result.Result;

import java.util.List;

public interface CouponService {
    Result<List<CouponVo>> getCouponsByUserId();

    Result<Void> useCoupon(Long couponId);

    Result<Void> insertCoupon(Coupon coupon);

}
