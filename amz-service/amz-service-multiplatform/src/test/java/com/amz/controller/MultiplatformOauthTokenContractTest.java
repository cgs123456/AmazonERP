package com.amz.controller;

import com.amz.model.OauthToken;
import com.amz.model.OauthTokenRequest;
import com.amz.service.impl.MultiplatformServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth Token 签发端点的传输位契约（#57 收口）。
 * <p>
 * 密钥只允许随 JSON 请求体传输：query 串会进 ingress 层访问日志。
 * 本端点不在网关白名单内、前端也无调用方，不存在需要兼容期的外部调用方；
 * 这三道钉子防止传输位在后续维护中被改回 query。
 */
@DisplayName("OAuth Token 签发：密钥只走请求体，query 传输位必须被拒绝")
class MultiplatformOauthTokenContractTest {

    private MultiplatformServiceImpl multiplatformService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        multiplatformService = mock(MultiplatformServiceImpl.class);
        MultiplatformController controller = new MultiplatformController();
        java.lang.reflect.Field field =
                MultiplatformController.class.getDeclaredField("multiplatformService");
        field.setAccessible(true);
        field.set(controller, multiplatformService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("JSON 请求体正常签发：四个字段透传服务层，返回体携带 token")
    void jsonBodyRoutesToService() throws Exception {
        OauthToken token = new OauthToken();
        token.setAccessToken("oat_fixed-by-mock");
        token.setTokenType("Bearer");
        when(multiplatformService.generateToken("ak", "sk", null, 1L)).thenReturn(token);

        mockMvc.perform(post("/multiplatform/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"ak\",\"appSecret\":\"sk\",\"shopId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("oat_fixed-by-mock"));

        verify(multiplatformService).generateToken("ak", "sk", null, 1L);
    }

    @Test
    @DisplayName("旧传输位（query 串）不再绑定：无请求体一律 4xx")
    void queryTransportIsRejected() throws Exception {
        mockMvc.perform(post("/multiplatform/oauth/token")
                        .queryParam("appKey", "ak")
                        .queryParam("appSecret", "sk")
                        .queryParam("shopId", "1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("反射钉：方法只收 @RequestBody 参数，且不得再出现 @RequestParam")
    void methodContractPinnedByReflection() {
        Method method = null;
        for (Method candidate : MultiplatformController.class.getDeclaredMethods()) {
            PostMapping mapping = candidate.getAnnotation(PostMapping.class);
            if (mapping != null
                    && java.util.Arrays.asList(mapping.value()).contains("/oauth/token")) {
                method = candidate;
                break;
            }
        }
        assertNotNull(method, "必须注册 POST /oauth/token 路由");
        for (Parameter parameter : method.getParameters()) {
            assertNull(parameter.getAnnotation(RequestParam.class),
                    "密钥不允许再从 query 串绑定: " + parameter.getName());
            assertNotNull(parameter.getAnnotation(RequestBody.class),
                    "签名请求必须整体走 JSON 请求体: " + parameter.getName());
            assertEquals(OauthTokenRequest.class, parameter.getType());
        }
        assertTrue(method.getParameterCount() == 1, "只允许一个请求体参数");
    }
}
