package com.amz.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * jwt 工具类（Spring Bean）。
 * 密钥通过配置注入，未配置时拒绝启动；签发/校验均带 issuer 与 audience。
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret-key:}")
    private String secretKey;

    @Value("${jwt.issuer:amz-erp}")
    private String issuer;

    @Value("${jwt.audience:amz-erp-client}")
    private String audience;

    /** Token 过期时间（毫秒），默认 86400000 = 24 小时，可通过 jwt.expire-time 配置覆盖 */
    @Value("${jwt.expire-time:86400000}")
    private long expireTime;

    /** Refresh Token 过期时间（毫秒），默认 604800000 = 7 天 */
    @Value("${jwt.refresh-expire-time:604800000}")
    private long refreshExpireTime;

    private static final String SHOPS_CLAIM = "shops";
    /** 角色 claim，值为 ADMIN/OPERATOR/VIEWER，未携带时默认 VIEWER（最小权限） */
    private static final String ROLE_CLAIM = "role";
    /** 默认角色：JWT 未显式携带 role 时使用 */
    private static final String DEFAULT_ROLE = "VIEWER";

    /** 供非 Spring 管理的组件（如 Netty WebSocketHandler）使用的静态实例 */
    private static volatile JwtUtil INSTANCE;

    @PostConstruct
    public void init() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret-key 未配置，拒绝启动。请通过环境变量 JWT_SECRET_KEY 或配置项 jwt.secret-key 提供密钥。");
        }
        INSTANCE = this;
        log.info("JwtUtil 初始化完成，issuer={}, audience={}", issuer, audience);
    }

    /** 供非 Spring 管理的组件调用 */
    public static JwtUtil getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("JwtUtil 尚未初始化，请确认 Spring 容器已启动且配置了 jwt.secret-key。");
        }
        return INSTANCE;
    }

    /**
     * 生成 token（不携带角色，默认 VIEWER）
     * @param userId 用户 id
     * @param shops  用户授权访问的店铺 id 列表（写入 shops claim）
     */
    public String createToken(Integer userId, List<Long> shops) {
        return createToken(userId, shops, DEFAULT_ROLE);
    }

    /**
     * 生成 token（携带角色 claim）
     * @param userId 用户 id
     * @param shops  用户授权访问的店铺 id 列表（写入 shops claim）
     * @param role   角色代码 ADMIN/OPERATOR/VIEWER（写入 role claim）
     */
    public String createToken(Integer userId, List<Long> shops, String role) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        List<String> shopStr = (shops == null || shops.isEmpty())
                ? List.of()
                : shops.stream().map(String::valueOf).toList();
        String roleVal = (role == null || role.isBlank()) ? DEFAULT_ROLE : role;
        return JWT.create()
                .withSubject(String.valueOf(userId))
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim(SHOPS_CLAIM, shopStr)
                .withClaim(ROLE_CLAIM, roleVal)
                .withExpiresAt(new Date(System.currentTimeMillis() + expireTime))
                .sign(algorithm);
    }

    /**
     * 解析 token，校验签名/issuer/audience/过期，返回 subject（userId）
     */
    public String parseToken(String token) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        DecodedJWT jwt = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
                .verify(token);
        return jwt.getSubject();
    }

    /**
     * 解析 token 中的 shops claim，返回店铺 id 列表
     */
    public List<Long> parseTokenShops(String token) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        DecodedJWT jwt = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
                .verify(token);
        List<String> shopStr = jwt.getClaim(SHOPS_CLAIM).asList(String.class);
        if (shopStr == null || shopStr.isEmpty()) {
            return new ArrayList<>();
        }
        return shopStr.stream().map(Long::valueOf).toList();
    }

    /**
     * 解析 token 中的 role claim。token 未携带 role 时返回 VIEWER（最小权限原则）。
     */
    public String parseTokenRole(String token) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        DecodedJWT jwt = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
                .verify(token);
        String role = jwt.getClaim(ROLE_CLAIM).asString();
        return (role == null || role.isBlank()) ? DEFAULT_ROLE : role;
    }

    /**
     * 生成 refresh token（仅含 userId + issuer + audience，不携带 shops/role 以减少泄露面）。
     * refresh token 有效期显著长于 access token（默认 7 天 vs 24h）。
     */
    public String createRefreshToken(Integer userId) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        return JWT.create()
                .withSubject("refresh:" + userId)
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim("type", "refresh")
                .withExpiresAt(new Date(System.currentTimeMillis() + refreshExpireTime))
                .sign(algorithm);
    }

    /**
     * 校验 refresh token 并返回 userId。
     * 仅校验签名/issuer/audience/过期/type=refresh，不校验 shops/role claim。
     * @return userId，失败抛异常
     */
    public Integer verifyRefreshToken(String refreshToken) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        DecodedJWT jwt = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
                .verify(refreshToken);
        String type = jwt.getClaim("type").asString();
        if (!"refresh".equals(type)) {
            throw new JWTDecodeException("非法的 refresh token type");
        }
        String subject = jwt.getSubject();
        if (subject == null || !subject.startsWith("refresh:")) {
            throw new JWTDecodeException("非法的 refresh token subject");
        }
        return Integer.valueOf(subject.substring("refresh:".length()));
    }
}
