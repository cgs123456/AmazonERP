package com.amz.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.util.Date;

/**
 * jwt工具类
 */
public class JwtUtil {
    // 从环境变量读取密钥，如果未配置则使用安全的默认密钥（64字符随机字符串）
    private static final String SECRET_KEY = System.getenv("JWT_SECRET_KEY") != null 
        ? System.getenv("JWT_SECRET_KEY") 
        : "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6A1B2C3D4E5F6";
    // 24小时过期
    private static final long EXPIRE_TIME = 24L * 60 * 60 * 1000;

    /**
     * 生成token
     * @param userId
     * @return
     */
    public static String createToken(Integer userId) {
        Algorithm algorithm = Algorithm.HMAC256(SECRET_KEY);
        return JWT.create()
                .withSubject(String.valueOf(userId))
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRE_TIME))
                .sign(algorithm);
    }

    /**
     * 解析token
     * @param token
     * @return
     */
    public static String parseToken(String token) {
        Algorithm algorithm = Algorithm.HMAC256(SECRET_KEY);
        return JWT.require(algorithm)
                .build()
                .verify(token)
                .getSubject();
    }
}
