package com.amz.model.dto;

import lombok.Data;

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
}
