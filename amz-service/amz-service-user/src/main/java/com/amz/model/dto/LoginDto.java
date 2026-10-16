package com.amz.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

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

    /**
     * 店铺级角色（shopId → ADMIN/OPERATOR/VIEWER），供 refresh token 换发 token 时重新签发。
     * <p>
     * 与 {@link #shops} 分开携带而不是合并成一个对象列表：{@code shops} 已被多处
     * 按「id 列表」消费，合并会波及全部读方。
     */
    private Map<Long, String> shopRoles;
}
