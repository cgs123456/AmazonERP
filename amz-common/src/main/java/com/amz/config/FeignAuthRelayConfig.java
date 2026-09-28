package com.amz.config;

import com.amz.context.UserContext;
import com.amz.security.InternalServiceTokenService;
import com.amz.util.JwtUtil;
import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * Feign 凭据透传配置。
 * <p>
 * 普通业务调用优先透传用户 JWT；调用 {@code /internal/**} 时强制改用内部服务令牌，
 * 并移除用户 JWT、shopId 与 userId，避免把终端用户身份带入服务间信任边界。
 * 无用户凭据的普通调用会附加服务令牌；下游仅对声明
 * {@code @InternalServiceAccess} 的端点接受该身份，其他端点仍返回 401。
 * <p>
 * <b>用户调用取值优先级</b>：
 * <ol>
 *   <li>Feign 方法上已显式声明 {@code token} 头 → 尊重调用方，不覆盖；</li>
 *   <li>存在当前 HTTP 请求上下文 → 透传原请求的 {@code token} 与 {@code shopId}；</li>
 *   <li>无请求上下文但 {@link UserContext} 已被手工填充 → 用 {@link JwtUtil} 现签一枚语义等价的 token；</li>
 *   <li>以上皆不满足 → 附加短时服务令牌，等待下游白名单校验。</li>
 * </ol>
 * <p>
 * <b>已知限制</b>：{@link RequestContextHolder} 基于 ThreadLocal，
 * {@code @Async} / 线程池中发起的 Feign 调用会丢失请求上下文，
 * 需调用方显式执行 {@code RequestContextHolder.setRequestAttributes(attrs, true)}
 * 或预先填充 {@link UserContext}。
 * <p>
 * 仅在类路径存在 OpenFeign 与 Servlet API 时生效，网关（WebFlux，且未引入 Feign）自动跳过。
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = {
        "feign.RequestInterceptor",
        "jakarta.servlet.http.HttpServletRequest"
})
public class FeignAuthRelayConfig {

    /** 用户 JWT 请求头名，须与 BaseAuthInterceptor / MyGlobalFilter 保持一致。 */
    private static final String TOKEN_HEADER = "token";

    /** 当前操作店铺请求头名，须与 BaseAuthInterceptor 保持一致。 */
    private static final String SHOP_ID_HEADER = "shopId";

    /** 网关/下游可能使用的用户 id 请求头；内部调用必须剥离。 */
    private static final String USER_ID_HEADER = "userId";

    @Bean
    public RequestInterceptor feignAuthRelayInterceptor(
            ObjectProvider<JwtUtil> jwtUtilProvider,
            InternalServiceTokenService internalServiceTokenService) {
        return template -> {
            if (isInternalPath(template.path())) {
                template.removeHeader(TOKEN_HEADER);
                template.removeHeader(SHOP_ID_HEADER);
                template.removeHeader(USER_ID_HEADER);
                template.removeHeader(InternalServiceTokenService.HEADER_NAME);
                template.header(InternalServiceTokenService.HEADER_NAME, internalServiceTokenService.createToken());
                return;
            }

            // 1) 调用方已显式指定用户凭据，不覆盖
            if (template.headers().containsKey(TOKEN_HEADER)) {
                return;
            }

            // 2) 请求上下文透传（覆盖绝大多数同步调用链）
            HttpServletRequest request = currentServletRequest();
            if (request != null) {
                String token = request.getHeader(TOKEN_HEADER);
                if (token != null && !token.isBlank()) {
                    template.header(TOKEN_HEADER, token);
                    String shopId = request.getHeader(SHOP_ID_HEADER);
                    if (shopId != null && !shopId.isBlank()) {
                        template.header(SHOP_ID_HEADER, shopId);
                    }
                    return;
                }
            }

            // 3) 无请求上下文但 UserContext 已填充：现签等价 token
            Integer userId = UserContext.getUserId();
            JwtUtil jwtUtil = jwtUtilProvider.getIfAvailable();
            if (userId != null && jwtUtil != null) {
                List<Long> shops = UserContext.getShops();
                String minted = jwtUtil.createToken(userId, shops == null ? List.of() : shops, UserContext.getRole());
                template.header(TOKEN_HEADER, minted);
                Long shopId = UserContext.getShopId();
                if (shopId != null) {
                    template.header(SHOP_ID_HEADER, String.valueOf(shopId));
                }
                return;
            }

            // 4) 无用户凭据：附加服务令牌。只有声明 @InternalServiceAccess 的下游端点会接受。
            if (!template.headers().containsKey(InternalServiceTokenService.HEADER_NAME)) {
                template.header(InternalServiceTokenService.HEADER_NAME, internalServiceTokenService.createToken());
            }
            log.debug("Feign 调用未携带用户凭据，已附加服务令牌：{} {}",
                    template.method(), template.path());
        };
    }

    private static boolean isInternalPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path;
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        return "/internal".equals(normalized) || normalized.startsWith("/internal/");
    }

    /**
     * 获取当前线程绑定的 Servlet 请求；不在请求线程（MQ / 定时任务 / 异步线程）时返回 {@code null}。
     */
    private static HttpServletRequest currentServletRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }
}
