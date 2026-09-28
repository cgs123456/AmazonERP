package com.amz.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 内部服务身份令牌。
 *
 * <p>服务间调用不能复用用户 JWT：用户 JWT 代表终端用户身份，而内部令牌只代表
 * 调用方服务。两类令牌使用不同 issuer、audience 与 type，即使共享当前过渡期的
 * HMAC 密钥也不能互相冒充。</p>
 *
 * <p>当前实现复用 {@code jwt.secret-key} 是为了兼容现有部署。生产强化阶段应改为
 * 独立密钥、短 TTL、mTLS/服务网格身份，并按“调用方服务 → 被调接口”做最小授权。</p>
 */
@Component
public class InternalServiceTokenService {

    public static final String HEADER_NAME = "X-Amz-Erp-Service-Token";
    public static final String TOKEN_TYPE = "internal-service";

    private static final String TYPE_CLAIM = "type";

    private final String secretKey;
    private final String issuer;
    private final String audience;
    private final String serviceName;
    private final long ttlSeconds;

    public InternalServiceTokenService(
            @Value("${jwt.secret-key:}") String secretKey,
            @Value("${jwt.internal.issuer:amz-erp-internal}") String issuer,
            @Value("${jwt.internal.audience:amz-erp-service}") String audience,
            @Value("${spring.application.name:}") String serviceName,
            @Value("${jwt.internal.expire-time-seconds:120}") long ttlSeconds) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret-key 未配置，无法签发或校验内部服务令牌。");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalStateException(
                    "spring.application.name 未配置，拒绝创建无身份的内部服务令牌。");
        }
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalStateException("内部服务令牌的 issuer/audience 不能为空。");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalStateException("内部服务令牌 TTL 必须大于 0 秒。");
        }
        this.secretKey = secretKey;
        this.issuer = issuer;
        this.audience = audience;
        this.serviceName = serviceName;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 为当前应用签发一枚短时内部服务令牌。
     */
    public String createToken() {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withSubject(serviceName)
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim(TYPE_CLAIM, TOKEN_TYPE)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + ttlSeconds * 1000L))
                .sign(Algorithm.HMAC256(secretKey));
    }

    /**
     * 校验内部服务令牌并返回调用方服务名。
     *
     * <p>任何签名、issuer、audience、过期时间、type 或 subject 校验失败均抛异常，
     * 调用方必须按 401 fail-closed 处理。</p>
     */
    public String verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JWTDecodeException("内部服务令牌为空");
        }
        DecodedJWT jwt = JWT.require(Algorithm.HMAC256(secretKey))
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
                .verify(token);
        String type = jwt.getClaim(TYPE_CLAIM).asString();
        if (!TOKEN_TYPE.equals(type)) {
            throw new JWTDecodeException("非法的内部服务令牌 type");
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new JWTDecodeException("内部服务令牌缺少服务身份");
        }
        return subject;
    }
}
