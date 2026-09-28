package com.amz.security;

import com.amz.annotation.InternalServiceAccess;
import com.amz.config.FeignAuthRelayConfig;
import com.amz.context.UserContext;
import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.util.JwtUtil;
import feign.Request;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("内部服务认证：/internal 必须只接受带签名和有效期的服务身份")
class InternalServiceAuthContractTest {

    private static final String SECRET = "internal-service-auth-test-secret-32-bytes-minimum";

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("服务令牌必须可签发并验证服务名")
    void serviceTokenRoundTripReturnsServiceIdentity() {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);

        String token = tokens.createToken();
        String service = tokens.verify(token);

        assertEquals("amz-service-ai", service);
    }

    @Test
    @DisplayName("不同 audience 的服务令牌必须被拒绝")
    void tokenWithDifferentAudienceIsRejected() {
        InternalServiceTokenService issuer = new InternalServiceTokenService(
                SECRET, "amz-erp-internal", "amz-erp-service-a", "amz-service-ai", 60);
        InternalServiceTokenService verifier = new InternalServiceTokenService(
                SECRET, "amz-erp-internal", "amz-erp-service-b", "amz-service-ai", 60);

        assertThrows(RuntimeException.class, () -> verifier.verify(issuer.createToken()));
    }

    @Test
    @DisplayName("被篡改的服务令牌必须被拒绝")
    void tamperedTokenIsRejected() {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);
        String token = tokens.createToken();
        int signatureStart = token.lastIndexOf('.') + 1;
        int tamperIndex = signatureStart + (token.length() - signatureStart) / 2;
        char replacement = token.charAt(tamperIndex) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, tamperIndex)
                + replacement
                + token.substring(tamperIndex + 1);

        assertThrows(RuntimeException.class, () -> tokens.verify(tampered));
    }

    @Test
    @DisplayName("缺少服务令牌的 /internal 请求必须 401")
    void missingServiceTokenIsRejected() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/internal/message/notify");

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(mock(JwtUtil.class), tokens("amz-service-ai", 60));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("用户 JWT 不能冒充内部服务令牌")
    void userJwtCannotAccessInternalEndpoint() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/internal/message/notify");
        when(request.getHeader("token")).thenReturn("user-jwt");
        JwtUtil jwtUtil = mock(JwtUtil.class);
        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(jwtUtil, tokens("amz-service-ai", 60));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(jwtUtil, never()).parseToken("user-jwt");
    }

    @Test
    @DisplayName("有效服务令牌可访问 /internal 并注入服务身份")
    void validServiceTokenReachesInternalEndpoint() throws Exception {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/internal/message/notify");
        when(request.getHeader(InternalServiceTokenService.HEADER_NAME)).thenReturn(tokens.createToken());

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(mock(JwtUtil.class), tokens);

        assertTrue(interceptor.preHandle(request, response, allowedHandler()));
        assertEquals("amz-service-ai", UserContext.getInternalService());
    }

    @Test
    @DisplayName("未被端点白名单允许的服务身份必须 403")
    void serviceNotAllowedByEndpointPolicyIsRejected() throws Exception {
        InternalServiceTokenService tokens = tokens("amz-service-ad", 60);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/internal/message/notify");
        when(request.getHeader(InternalServiceTokenService.HEADER_NAME)).thenReturn(tokens.createToken());

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(mock(JwtUtil.class), tokens);

        assertFalse(interceptor.preHandle(request, response, allowedHandler()));
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("未声明服务白名单的内部端点必须 fail-closed")
    void unannotatedInternalEndpointIsRejected() throws Exception {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/internal/unclassified/notify");
        when(request.getHeader(InternalServiceTokenService.HEADER_NAME)).thenReturn(tokens.createToken());

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(mock(JwtUtil.class), tokens);
        HandlerMethod handler = new HandlerMethod(
                new UnclassifiedInternalEndpoint(),
                UnclassifiedInternalEndpoint.class.getDeclaredMethod("handle"));

        assertFalse(interceptor.preHandle(request, response, handler));
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("普通端点显式声明双信任后，有效服务令牌可访问并注入服务身份")
    void validServiceTokenCanAccessDualTrustBusinessEndpoint() throws Exception {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/spapi/dual-trust/read");
        when(request.getHeader(InternalServiceTokenService.HEADER_NAME)).thenReturn(tokens.createToken());

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(mock(JwtUtil.class), tokens);

        assertTrue(interceptor.preHandle(request, response, dualTrustHandler()));
        assertEquals("amz-service-ai", UserContext.getInternalService());
    }

    @Test
    @DisplayName("普通端点未声明双信任时，服务令牌必须 401")
    void unannotatedBusinessEndpointRejectsServiceToken() throws Exception {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/spapi/unclassified/read");
        when(request.getHeader(InternalServiceTokenService.HEADER_NAME)).thenReturn(tokens.createToken());

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(mock(JwtUtil.class), tokens);
        HandlerMethod handler = new HandlerMethod(
                new UnclassifiedBusinessEndpoint(),
                UnclassifiedBusinessEndpoint.class.getDeclaredMethod("handle"));

        assertFalse(interceptor.preHandle(request, response, handler));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("用户 JWT 与服务令牌同时存在时，用户身份优先且不得注入服务身份")
    void userJwtTakesPrecedenceOverServiceToken() throws Exception {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/spapi/dual-trust/read");
        when(request.getHeader("token")).thenReturn("user-jwt");
        when(request.getHeader(InternalServiceTokenService.HEADER_NAME)).thenReturn(tokens.createToken());
        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.parseToken("user-jwt")).thenReturn("1");
        when(jwtUtil.parseTokenRole("user-jwt")).thenReturn("VIEWER");
        when(jwtUtil.parseTokenShops("user-jwt")).thenReturn(java.util.List.of(1001L));

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(jwtUtil, tokens);

        assertTrue(interceptor.preHandle(request, response, dualTrustHandler()));
        assertEquals(Integer.valueOf(1), UserContext.getUserId());
        assertNull(UserContext.getInternalService());
    }

    @Test
    @DisplayName("Feign 调用 /internal 时必须自动附加服务令牌且不得附加用户 JWT")
    void feignInternalCallAddsServiceTokenOnly() {
        InternalServiceTokenService tokens = tokens("amz-service-ai", 60);
        @SuppressWarnings("unchecked")
        ObjectProvider<JwtUtil> jwtProvider = mock(ObjectProvider.class);
        RequestInterceptor interceptor = new FeignAuthRelayConfig().feignAuthRelayInterceptor(jwtProvider, tokens);
        RequestTemplate template = new RequestTemplate()
                .method(Request.HttpMethod.POST)
                .uri("/internal/message/notify");

        interceptor.apply(template);

        assertTrue(template.headers().containsKey(InternalServiceTokenService.HEADER_NAME));
        assertFalse(template.headers().containsKey("token"));
        String serviceToken = template.headers().get(InternalServiceTokenService.HEADER_NAME).iterator().next();
        assertEquals("amz-service-ai", tokens.verify(serviceToken));
    }

    private static HandlerMethod allowedHandler() throws NoSuchMethodException {
        return new HandlerMethod(
                new AllowedInternalEndpoint(),
                AllowedInternalEndpoint.class.getDeclaredMethod("handle"));
    }

    private static HandlerMethod dualTrustHandler() throws NoSuchMethodException {
        return new HandlerMethod(
                new DualTrustBusinessEndpoint(),
                DualTrustBusinessEndpoint.class.getDeclaredMethod("handle"));
    }

    static class AllowedInternalEndpoint {
        @InternalServiceAccess("amz-service-ai")
        public void handle() {
        }
    }

    static class UnclassifiedInternalEndpoint {
        public void handle() {
        }
    }

    static class DualTrustBusinessEndpoint {
        @InternalServiceAccess("amz-service-ai")
        public void handle() {
        }
    }

    static class UnclassifiedBusinessEndpoint {
        public void handle() {
        }
    }

    private static InternalServiceTokenService tokens(String serviceName, long ttlSeconds) {
        return new InternalServiceTokenService(
                SECRET, "amz-erp-internal", "amz-erp-service", serviceName, ttlSeconds);
    }
}
