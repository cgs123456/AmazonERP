package com.amz.config;

import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局鉴权拦截器注册（所有业务微服务共用的唯一来源）。
 * <p>
 * <b>背景</b>：此前仅 user / product / search / order 四个服务各自维护了一份内容几乎相同的
 * {@code WebMvcConfig}，其余 11 个服务未注册任何鉴权拦截器。后果有二：
 * <ul>
 *   <li>这些服务的 {@link com.amz.context.UserContext} 始终为空，
 *       依赖它的 {@code @ShopScoped} 越权校验与 {@code @FieldPermission} 字段级脱敏
 *       全部形同虚设——切面读到 null 后无从判定，多租户隔离在这些服务上等于未启用；</li>
 *   <li>一旦绕过网关直连服务 ClusterIP，即可无凭据访问全部业务接口。</li>
 * </ul>
 * 本类将注册逻辑上收到 amz-common，由组件扫描自动装配到每个业务服务，消除漏配与重复维护。
 * <p>
 * <b>放行策略</b>：拦截路径为 {@code /**}，免鉴权名单统一由
 * {@link BaseAuthInterceptor} 内部的 {@code WHITE_LIST} 维护
 * （{@code /user/send}、{@code /user/verify}、{@code /internal}、{@code /actuator}），
 * 此处不再重复声明 {@code excludePathPatterns}，避免两处白名单漂移。
 * <p>
 * <b>生效条件</b>：仅在 Servlet Web 应用且类路径存在 Spring MVC 时装配。
 * 网关为 WebFlux 应用且已排除 {@code spring-boot-starter-web}，会被条件自动跳过
 * （网关侧鉴权由 {@code MyGlobalFilter} 独立承担）。
 */
@Slf4j
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.config.annotation.WebMvcConfigurer")
public class GlobalAuthWebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;

    public GlobalAuthWebMvcConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BaseAuthInterceptor(jwtUtil))
                .addPathPatterns("/**")
                .order(0);
        log.info("已注册全局鉴权拦截器 BaseAuthInterceptor，拦截 /**，白名单由 BaseAuthInterceptor.WHITE_LIST 统一维护");
    }
}
