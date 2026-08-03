package com.amz.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class LoginDto {
    /**
     * 手机号
     */
    private String phone;

    /**
     * 验证码
     */
    private String code;

    /**
     * 用户角色（ADMIN/OPERATOR/VIEWER），供 refresh token 换发 token 时重新签发
     */
    private String role;

    /**
     * 用户授权的店铺 id 列表，供 refresh token 换发 token 时重新签发
     */
    private List<Long> shops;
}
