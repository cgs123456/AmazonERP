package com.amz.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.Collections;
import java.util.List;

@Configuration
public class SentinelGatewayConfig {

    private final List<ViewResolver> viewResolvers;

    public SentinelGatewayConfig(ObjectProvider<List<ViewResolver>> viewResolversProvider) {
        this.viewResolvers = viewResolversProvider.getIfAvailable(Collections::emptyList);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler() {
        return new SentinelGatewayBlockExceptionHandler(viewResolvers, null);
    }

    @Bean
    @Order(-1)
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    @PostConstruct
    public void doInit() {
        BlockRequestHandler blockHandler = (exchange, t) ->
                ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(BodyInserters.fromValue(
                                "{\"ok\":false,\"code\":429,\"message\":\"请求过于频繁，请稍后重试\"}"));
        GatewayCallbackManager.setBlockHandler(blockHandler);

        // 内置基线限流规则：旧实现仅注册了 BlockHandler，规则完全依赖外部 Dashboard 推送，
        // Dashboard 不可达时限流形同虚设（含免鉴权的 /user/send 短信端点可被刷量）。
        // 此处以路由维度写入保守默认值，Dashboard 推送的规则仍可覆盖/扩展。
        com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule sms =
                new com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule("amz-service-user");
        sms.setResourceMode(com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID);
        sms.setCount(5);                    // /user/** 整体 5 QPS，短信验证码类接口防刷优先
        sms.setIntervalSec(1);
        com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule aiRoute =
                new com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule("amz-service-ai");
        aiRoute.setResourceMode(com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID);
        aiRoute.setCount(20);               // AI 服务单次调用成本高，限制并发突发
        aiRoute.setIntervalSec(1);
        com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule spapiRoute =
                new com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule("amz-service-spapi");
        spapiRoute.setResourceMode(com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID);
        spapiRoute.setCount(50);
        spapiRoute.setIntervalSec(1);
        com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager.loadRules(
                java.util.Set.of(sms, aiRoute, spapiRoute));
    }
}
