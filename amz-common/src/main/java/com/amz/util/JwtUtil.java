package com.amz.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /**
     * 店铺级角色 claim：{@code {shopId: "OPERATOR", ...}}，只含该用户已绑定的店铺。
     * <p>
     * 与 {@link #SHOPS_CLAIM} 分开而不是把 shops 改成对象数组：{@code shops} 已被
     * 网关、各服务切面、前端多处按「字符串 id 列表」消费，改结构会波及全部读方。
     * 新 claim 缺席时下游自动回落全局 role，老 token 无需失效。
     */
    private static final String SHOP_ROLES_CLAIM = "shop_roles";
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
     * 生成 token（携带全局角色 claim，不带店铺级角色）
     * @param userId 用户 id
     * @param shops  用户授权访问的店铺 id 列表（写入 shops claim）
     * @param role   角色代码 ADMIN/OPERATOR/VIEWER（写入 role claim）
     */
    public String createToken(Integer userId, List<Long> shops, String role) {
        return createToken(userId, shops, role, null);
    }

    /**
     * 生成 token（携带全局角色 + 店铺级角色）。
     *
     * @param userId    用户 id
     * @param shops     用户授权访问的店铺 id 列表（写入 shops claim）
     * @param role      全局角色代码，见 {@link #ROLE_CLAIM}
     * @param shopRoles 店铺级角色（shopId → 角色）。传 {@code null} 或空表示不带该 claim，
     *                  下游按 {@code resolveRole} 回落全局角色——这正是老 token 的行为，
     *                  所以本 claim 的引入不需要让已签发的 token 失效。
     */
    public String createToken(Integer userId, List<Long> shops, String role,
                              Map<Long, String> shopRoles) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        List<String> shopStr = (shops == null || shops.isEmpty())
                ? List.of()
                : shops.stream().map(String::valueOf).toList();
        String roleVal = (role == null || role.isBlank()) ? DEFAULT_ROLE : role;
        JWTCreator.Builder builder = JWT.create()
                .withSubject(String.valueOf(userId))
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim(SHOPS_CLAIM, shopStr)
                .withClaim(ROLE_CLAIM, roleVal)
                .withExpiresAt(new Date(System.currentTimeMillis() + expireTime));
        if (shopRoles != null && !shopRoles.isEmpty()) {
            // 键统一转 String：JSON 对象的键只能是字符串，这里转一次，
            // 解析侧就不用猜 java-jwt 有没有替我们转。
            Map<String, String> asStrings = new LinkedHashMap<>(shopRoles.size());
            for (Map.Entry<Long, String> e : shopRoles.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank()) {
                    asStrings.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            if (!asStrings.isEmpty()) {
                builder.withClaim(SHOP_ROLES_CLAIM, asStrings);
            }
        }
        return builder.sign(algorithm);
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
        // 脏 claim 容错：逐个安全解析，非法条目跳过（避免 NumberFormatException 逃逸成 500）
        List<Long> shopIds = new ArrayList<>(shopStr.size());
        for (String s : shopStr) {
            try {
                shopIds.add(Long.valueOf(s));
            } catch (NumberFormatException e) {
                // 忽略非法店铺 id（鉴权拦截器会按 401 处理无有效店铺的 token 场景）
            }
        }
        return shopIds;
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
     * 解析 token 中的 {@code shop_roles} claim，返回 shopId → 角色。
     * <p>
     * 老 token 没有该 claim（或 claim 为空）时返回空 Map，调用方据此回落全局角色。
     * 脏条目逐个跳过，与 {@link #parseTokenShops} 同口径——不让一个非法键把整个请求打成 500。
     */
    public Map<Long, String> parseTokenShopRoles(String token) {
        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        DecodedJWT jwt = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
                .verify(token);
        Map<String, Object> raw;
        try {
            raw = jwt.getClaim(SHOP_ROLES_CLAIM).asMap();
        } catch (JWTDecodeException e) {
            // claim 存在但不是对象（例如被塞了字符串）：当作「没有店铺级角色」，
            // 不抛给调用方——鉴权不该因为一个畸形可选 claim 整体失败。
            log.warn("shop_roles claim 结构非法，已忽略并回落全局角色: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<Long, String> result = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            try {
                result.put(Long.valueOf(e.getKey()), String.valueOf(e.getValue()));
            } catch (NumberFormatException ignored) {
                // 忽略非法店铺 id
            }
        }
        return result;
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
