package com.amz.config;

import com.amz.context.UserContext;
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
 * Feign 用户凭据透传配置。
 * <p>
 * <b>背景</b>：各业务微服务通过 {@code BaseAuthInterceptor} 校验 {@code token} 请求头，
 * 但 Feign 默认<b>不会</b>把调用方收到的请求头带到下游。若不透传，
 * 所有跨服务调用（如 report → order 的 {@code /order/profit/summary/{shopId}}）
 * 都会被下游拦截器判定为缺少 token 而返回 401，
 * 并被 {@code fallbackFactory} 静默降级成空数据——表现为报表数据缺失而非显式报错，极难排查。
 * <p>
 * <b>取值优先级</b>：
 * <ol>
 *   <li>Feign 方法上已显式声明 {@code token} 头 → 尊重调用方，不覆盖；</li>
 *   <li>存在当前 HTTP 请求上下文 → 透传原请求的 {@code token} 与 {@code shopId}，
 *       下游据此还原完整 {@link UserContext}，{@code @ShopScoped} 越权校验链得以贯通；</li>
 *   <li>无请求上下文（MQ 消费者 / {@code @Scheduled} 线程）但 {@link UserContext} 已被手工填充
 *       → 用 {@link JwtUtil} 现签一枚语义等价的 token；</li>
 *   <li>以上皆不满足 → 不加凭据。此时下游必须命中白名单（{@code /internal/**}、
 *       {@code /actuator/**}）才能放行，否则应视为设计缺陷。</li>
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

    @Bean
    public RequestInterceptor feignAuthRelayInterceptor(ObjectProvider<JwtUtil> jwtUtilProvider) {
        return template -> {
            // 1) 调用方已显式指定凭据，不覆盖
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

            // 4) 无凭据可用：下游必须命中白名单，否则将 401
            log.debug("Feign 调用未携带用户凭据（无请求上下文且 UserContext 为空）：{} {}",
                    template.method(), template.path());
        };
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
