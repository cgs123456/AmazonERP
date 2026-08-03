package com.amz.controller;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.amz.result.Result;
import com.amz.service.LoginService;
import com.amz.model.dto.LoginDto;
import com.amz.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class LoginController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping("/send/{phone}")
    public Result<String> send(@PathVariable String phone) {
        return loginService.send(phone);
    }

    /**
     * 校验短信验证码并签发 JWT。
     * <p>
     * 使用 POST 而非 GET 的原因：
     * 1. 该操作有副作用（消费一次性验证码、签发 token），不满足 GET 的幂等/安全语义；
     * 2. GET 会把 phone/code 写入 URL，进而落入网关访问日志、浏览器历史与 Referer 头，
     *    构成凭据泄露面。
     * <p>
     * {@code LoginDto} 无 {@code @RequestBody} 注解，Spring 按普通 POJO 从
     * query string 绑定，与前端 {@code request.post('/user/verify', null, {params:{phone, code}})}
     * 的调用形式一致。
     */
    @PostMapping("/verify")
    public Result<Map<String, String>> verify(LoginDto loginDto) {
        return loginService.verify(loginDto);
    }

    /**
     * 刷新 access token。前端 access token 过期后，用 refresh token 换取新的 access + refresh token 对。
     * 只接受 refresh token（type=refresh），拒绝普通 access token。
     */
    @PostMapping("/refresh")
    public Result<Map<String, String>> refreshToken(@RequestHeader("token") String refreshToken) {
        try {
            Integer userId = jwtUtil.verifyRefreshToken(refreshToken);
            // 重新查询用户 shops 和 role（确保权限变更实时生效）
            LoginDto user = loginService.getUserById(userId);
            if (user == null) {
                return Result.failure("用户不存在");
            }
            String newAccessToken = jwtUtil.createToken(userId, user.getShops(), user.getRole());
            String newRefreshToken = jwtUtil.createRefreshToken(userId);
            Map<String, String> tokens = new HashMap<>();
            tokens.put("token", newAccessToken);
            tokens.put("refreshToken", newRefreshToken);
            return Result.success(tokens);
        } catch (JWTDecodeException | TokenExpiredException e) {
            return Result.failure("refresh token 无效或已过期，请重新登录");
        }
    }
}
