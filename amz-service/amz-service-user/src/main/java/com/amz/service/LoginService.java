package com.amz.service;

import com.amz.result.Result;
import com.amz.model.dto.LoginDto;

import java.util.Map;

public interface LoginService {
    Result<String> send(String phone);

    /**
     * 校验短信验证码并签发 access token + refresh token
     * @return Map 包含 token（access token）和 refreshToken
     */
    Result<Map<String, String>> verify(LoginDto loginDto);

    /**
     * 根据 userId 查询用户信息（shops + role），供 refresh token 换发新 access token 使用
     */
    LoginDto getUserById(Integer userId);
}
