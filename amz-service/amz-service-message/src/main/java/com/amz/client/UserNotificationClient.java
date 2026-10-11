package com.amz.client;

import com.amz.http.ResilientHttpClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知落库客户端（message → user，P1.6）。
 * <p>
 * <b>存在动因</b>：站内通知此前只走 WebSocket 推送，用户不在线即丢、无历史——
 * 「库存预警/订单异常」错过就是事故。本客户端在推送时同步落库到 user 服务
 * （通知按用户归属，amz_user 库持有该表；本服务刻意无数据库）。
 * <p>
 * <b>为什么不用声明式 Feign 客户端</b>（重要，踩过一次）：message 服务是无 datasource/flyway 的
 * 轻模块，CI 的 runtime-smoke 用「无任何中间件、无密钥」启动它作为启动回归门禁。
 * 引入声明式 Feign 客户端后 CI（JDK 17）装配失败——`No bean found of type feign.codec.Encoder`，
 * 进程 8 秒内退出，冒烟腿红（2026-10-11 实测；本地 JDK 21 侥幸能起，属环境差异掩盖了问题）。
 * 改用「DiscoveryClient 解析地址 + amz-common ResilientHttpClient（自带熔断/重试）」，
 * message 不新增任何依赖，也不触发 Servlet Web 自动配置链。
 * <p>
 * 注：本类注释刻意不写那个注解的字面量——`FeignFallbackWiringContractTest` 用源码文本扫描
 * 判定「哪些模块有 Feign 客户端」（注释里的字面量会被误判为存在客户端），
 * 这是仓库既有扫描型契约的通病（坑 12：注释不是调用）。
 * <p>
 * <b>降级</b>：地址解析失败或调用失败都只记 warn 并返回 false——落库是旁路，
 * 不能因为落库挂掉而阻断推送；但绝不返回 true 假装已落库。
 */
@Slf4j
@Component
public class UserNotificationClient {

    /** user 服务名（与 Nacos 注册名一致）。 */
    static final String USER_SERVICE = "amz-service-user";

    private static final Gson GSON = new Gson();

    private final ObjectProvider<DiscoveryClient> discoveryClientProvider;
    private final ObjectProvider<ResilientHttpClient> httpClientProvider;

    /** 地址覆盖（测试/单机联调用；留空则走服务发现）。 */
    @Value("${amz.message.user-service-base-url:}")
    private String baseUrlOverride = "";

    public UserNotificationClient(ObjectProvider<DiscoveryClient> discoveryClientProvider,
                                  ObjectProvider<ResilientHttpClient> httpClientProvider) {
        this.discoveryClientProvider = discoveryClientProvider;
        this.httpClientProvider = httpClientProvider;
    }

    /**
     * 落库一条通知。
     *
     * @param userId  接收用户
     * @param type    消息类型（MessageTypeEnum 序号）
     * @param title   标题
     * @param content 内容
     * @return true=落库成功；false=未落库（降级/失败，调用方记 warn 但继续推送）
     */
    public boolean save(Long userId, Integer type, String title, String content) {
        if (userId == null) {
            return false;
        }
        String baseUrl = resolveBaseUrl();
        if (baseUrl == null) {
            log.warn("通知落库跳过：无法解析 {} 服务地址（推送不受影响）", USER_SERVICE);
            return false;
        }
        ResilientHttpClient httpClient = httpClientProvider.getIfAvailable();
        if (httpClient == null) {
            log.warn("通知落库跳过：ResilientHttpClient 未装配（推送不受影响）");
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("type", type == null ? 0 : type);
        body.put("title", title);
        body.put("content", content);
        try {
            String response = httpClient.post(USER_SERVICE, baseUrl + "/user/notification",
                    Map.of(), GSON.toJson(body));
            // 只认「响应体是 JSON 且 code=200」：user 服务业务失败也返回 200 HTTP + code!=200
            JsonObject json = GSON.fromJson(response, JsonObject.class);
            boolean ok = json != null && json.has("code") && json.get("code").getAsInt() == 200;
            if (!ok) {
                log.warn("通知落库失败（推送不受影响）userId={} response={}", userId, response);
            }
            return ok;
        } catch (Exception e) {
            log.warn("通知落库异常（推送不受影响）userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    /** 解析 user 服务基址：优先配置覆盖，其次服务发现首个实例。 */
    private String resolveBaseUrl() {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            return baseUrlOverride.endsWith("/")
                    ? baseUrlOverride.substring(0, baseUrlOverride.length() - 1) : baseUrlOverride;
        }
        DiscoveryClient discoveryClient = discoveryClientProvider.getIfAvailable();
        if (discoveryClient == null) {
            return null;
        }
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(USER_SERVICE);
            if (instances == null || instances.isEmpty()) {
                return null;
            }
            ServiceInstance instance = instances.get(0);
            return instance.getUri().toString();
        } catch (Exception e) {
            log.warn("服务发现查询 {} 失败：{}", USER_SERVICE, e.getMessage());
            return null;
        }
    }
}
