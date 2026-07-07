package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("amz_user_coupon")
public class UserCoupon implements Serializable {
    /**
     * 用户id
     */
    @TableField("user_id")
    private Integer userId;

    /**
     * 优惠券id
     */
    @TableField("coupon_id")
    private Integer couponId;
}
