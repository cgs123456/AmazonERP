package com.amz.model.vo;

import com.amz.model.pojo.Coupon;
import lombok.Data;

@Data
public class CouponVo {

    private Coupon coupon;

    /**
     * 是否可用
     */
    private Boolean isUsable;
}
