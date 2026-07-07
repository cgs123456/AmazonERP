package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-店铺关联表（多店铺 RBAC 权限）。
 * 一个用户可关联多个店铺，role 决定操作权限级别。
 */
@Data
@TableName("amz_user_shop")
public class UserShop {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long shopId;

    private String role;  // ADMIN/OPERATOR/VIEWER
}
