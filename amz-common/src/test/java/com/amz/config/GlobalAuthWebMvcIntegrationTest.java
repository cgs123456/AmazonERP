package com.amz.config;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.aspect.RequireRoleAspect;
import com.amz.aspect.ShopIdGuardAspect;
import com.amz.context.UserContext;
import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.result.Result;
import com.amz.security.InternalServiceTokenService;
import com.amz.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局鉴权 Web MVC 集成测试。
 *
 * <p>该测试刻意启动真实 {@link BaseAuthInterceptor}、Spring MVC 参数绑定和 AOP 代理，
 * 而不是直接调用 Controller/Aspect。用于防止“单元测试通过，但生产 Web 容器没有接入
 * {@link RequireRoleAspect} / {@link ShopIdGuardAspect}”这一类接线回归。</p>
 *
 * <p>证据等级：E2（进程内桩，不发真实 Amazon 请求）。</p>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        GlobalAuthWebMvcIntegrationTest.TestConfig.class,
        GlobalAuthWebMvcConfig.class
})
@TestPropertySource(properties = {
        "jwt.secret-key=test-secret-key-for-auth-integration",
        "jwt.issuer=amz-erp",
        "jwt.audience=amz-erp-client"
})
@DisplayName("全局鉴权：真实 MVC + AOP 链路必须 fail-closed")
class GlobalAuthWebMvcIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private InternalServiceTokenService internalServiceTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("缺少 token 的写请求必须被拦截器拒绝")
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(post("/test/auth/write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("VIEWER 不能通过 @RequireRole 执行写操作")
    void viewerCannotReachElevatedWriteEndpoint() throws Exception {
        String token = jwtUtil.createToken(1, List.of(1001L), "VIEWER");

        mockMvc.perform(post("/test/auth/write")
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));
    }

    @Test
    @DisplayName("OPERATOR 且店铺匹配时写操作才能到达控制器")
    void operatorWithMatchingShopCanReachWriteEndpoint() throws Exception {
        String token = jwtUtil.createToken(1, List.of(1001L), "OPERATOR");

        mockMvc.perform(post("/test/auth/write")
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    @DisplayName("已认证但无 shops claim 的 VIEWER 不能访问 @ShopScoped 接口")
    void authenticatedUserWithoutShopsCannotReachScopedEndpoint() throws Exception {
        String token = jwtUtil.createToken(1, List.of(), "VIEWER");

        mockMvc.perform(get("/test/auth/scoped")
                        .header("token", token)
                        .param("shopId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("没有任何店铺授权")));
    }

    @Test
    @DisplayName("已认证用户不能访问未授权店铺的 @ShopScoped 接口")
    void crossShopScopedRequestIsRejected() throws Exception {
        String token = jwtUtil.createToken(1, List.of(2002L), "VIEWER");

        mockMvc.perform(get("/test/auth/scoped")
                        .header("token", token)
                        .param("shopId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("不在授权范围")));
    }

    @Test
    @DisplayName("查询参数使用显式注解名称时仍拦截未授权店铺")
    void explicitRequestParamNameIsEnforcedByMvcChain() throws Exception {
        String token = jwtUtil.createToken(1, List.of(2002L), "VIEWER");

        mockMvc.perform(get("/test/auth/scoped-alias")
                        .header("token", token)
                        .param("shopId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("不在授权范围")));
    }

    @Test
    @DisplayName("路径变量使用显式注解名称时仍拦截未授权店铺")
    void explicitPathVariableNameIsEnforcedByMvcChain() throws Exception {
        String token = jwtUtil.createToken(1, List.of(2002L), "VIEWER");

        mockMvc.perform(get("/test/auth/scoped-path/1001")
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("不在授权范围")));
    }

    @Test
    @DisplayName("已认证但无 shops claim 的请求体 shopId 不能绕过通用店铺校验")
    void authenticatedUserWithoutShopsCannotBypassBodyShopCheck() throws Exception {
        String token = jwtUtil.createToken(1, List.of(), "VIEWER");

        mockMvc.perform(post("/test/auth/body-scope")
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权访问")));
    }

    @Test
    @DisplayName("已认证且命中授权店铺的请求体 shopId 可以通过通用店铺校验")
    void authenticatedUserWithMatchingShopCanPassBodyShopCheck() throws Exception {
        String token = jwtUtil.createToken(1, List.of(1001L), "OPERATOR");

        mockMvc.perform(post("/test/auth/body-scope")
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    @DisplayName("缺少服务令牌的 /internal 请求必须 401")
    void missingInternalServiceTokenIsRejected() throws Exception {
        mockMvc.perform(post("/internal/test/notify"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有效服务令牌可访问 /internal 并注入服务身份")
    void validInternalServiceTokenReachesEndpoint() throws Exception {
        String serviceToken = internalServiceTokenService.createToken();

        mockMvc.perform(post("/internal/test/notify")
                        .header(InternalServiceTokenService.HEADER_NAME, serviceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("amz-service-test"));
    }

    @Test
    @DisplayName("有效服务令牌可访问显式双信任的普通业务端点")
    void validServiceTokenReachesDualTrustBusinessEndpoint() throws Exception {
        String serviceToken = internalServiceTokenService.createToken();

        mockMvc.perform(post("/test/auth/service-write")
                        .header(InternalServiceTokenService.HEADER_NAME, serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("amz-service-test"));
    }

    @Test
    @DisplayName("双信任端点仍拒绝空 shopId")
    void dualTrustBusinessEndpointRejectsNullShopId() throws Exception {
        String serviceToken = internalServiceTokenService.createToken();

        mockMvc.perform(post("/test/auth/service-write")
                        .header(InternalServiceTokenService.HEADER_NAME, serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("shopId")));
    }

    @Test
    @DisplayName("用户 JWT 优先于服务令牌，VIEWER 不能借服务身份绕过角色")
    void userJwtPreventsServiceTokenRoleBypass() throws Exception {
        String userToken = jwtUtil.createToken(1, List.of(1001L), "VIEWER");
        String serviceToken = internalServiceTokenService.createToken();

        mockMvc.perform(post("/test/auth/service-write")
                        .header("token", userToken)
                        .header(InternalServiceTokenService.HEADER_NAME, serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));
    }

    @Test
    @DisplayName("未声明双信任的普通端点拒绝服务令牌")
    void unannotatedBusinessEndpointRejectsServiceToken() throws Exception {
        String serviceToken = internalServiceTokenService.createToken();

        mockMvc.perform(post("/test/auth/write")
                        .header(InternalServiceTokenService.HEADER_NAME, serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1001}"))
                .andExpect(status().isUnauthorized());
    }

    @Configuration
    @EnableWebMvc
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        JwtUtil jwtUtil() {
            return new JwtUtil();
        }

        @Bean
        InternalServiceTokenService internalServiceTokenService() {
            return new InternalServiceTokenService(
                    "test-secret-key-for-auth-integration",
                    "amz-erp-internal",
                    "amz-erp-service",
                    "amz-service-test",
                    60);
        }

        @Bean
        RequireRoleAspect requireRoleAspect() {
            return new RequireRoleAspect();
        }

        @Bean
        ShopIdGuardAspect shopIdGuardAspect() {
            return new ShopIdGuardAspect();
        }

        @Bean
        GuardedTestController guardedTestController() {
            return new GuardedTestController();
        }

        @Bean
        InternalTestController internalTestController() {
            return new InternalTestController();
        }
    }

    @RestController
    @RequestMapping("/test/auth")
    static class GuardedTestController {

        @RequireRole({"OPERATOR", "ADMIN"})
        @PostMapping("/write")
        Result<String> write(@RequestBody WriteRequest request) {
            return Result.success("ok");
        }

        @InternalServiceAccess("amz-service-test")
        @RequireRole({"OPERATOR", "ADMIN"})
        @PostMapping("/service-write")
        Result<String> serviceWrite(@RequestBody WriteRequest request) {
            if (!UserContext.isShopAllowedByUserOrTrustedService(request.getShopId())) {
                return Result.failure("shopId 不能为空或无权访问");
            }
            return Result.success(UserContext.getInternalService());
        }

        @PostMapping("/body-scope")
        Result<String> bodyScope(@RequestBody WriteRequest request) {
            return UserContext.isShopAllowed(request.getShopId())
                    ? Result.success("ok")
                    : Result.failure("无权访问该店铺数据");
        }

        @ShopScoped
        @GetMapping("/scoped")
        Result<String> scoped(@RequestParam Long shopId) {
            return Result.success("ok");
        }

        @ShopScoped
        @GetMapping("/scoped-alias")
        Result<String> scopedAlias(@RequestParam(name = "shopId") Long tenantId) {
            return Result.success("ok");
        }

        @ShopScoped
        @GetMapping("/scoped-path/{shopId}")
        Result<String> scopedPath(@PathVariable("shopId") Long tenantId) {
            return Result.success("ok");
        }
    }

    @RestController
    @RequestMapping("/internal/test")
    static class InternalTestController {

        @InternalServiceAccess("amz-service-test")
        @PostMapping("/notify")
        Result<String> notifyInternal() {
            return Result.success(UserContext.getInternalService());
        }
    }

    static class WriteRequest {
        private Long shopId;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }
    }
}
