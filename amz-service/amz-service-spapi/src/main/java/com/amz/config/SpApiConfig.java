package com.amz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * SP-API 全局配置，绑定 application.yml 中前缀为 "spapi" 的配置项。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spapi")
public class SpApiConfig {

    /**
     * AWS Access Key ID（IAM 长期凭证）。
     */
    private String awsAccessKey;

    /**
     * AWS Secret Access Key（IAM 长期凭证）。
     */
    private String awsSecretKey;

    /**
     * AWS 区域，默认 us-east-1。
     */
    private String region = "us-east-1";

    /**
     * Login with Amazon 获取 access_token 的端点。
     */
    private String lwaEndpoint = "https://api.amazon.com/auth/o2/token";

    /**
     * SP-API 基址覆盖（P0-51），对应环境变量 SPAPI_BASE_URL_OVERRIDE。
     * <p>
     * <b>仅限非生产</b>：用于把出站指向本地桩 / 录制回放代理，取得 E2 级证据。
     * prod profile 下非空即<b>拒绝启动</b>（{@code SpApiEndpointResolver} 构造期抛异常）；
     * 非生产也必须是官方主机或 {@code spapi.allowlist} 内的主机，否则同样拒绝。
     * 留空 = 按 marketplace 分组解析官方主机（NA/EU/FE）。
     */
    private String baseUrlOverride;

    /**
     * LWA token 端点覆盖（P0-51），对应环境变量 SPAPI_LWA_ENDPOINT_OVERRIDE。
     * <p>
     * 与 {@link #baseUrlOverride} 同一口径：仅非生产可用，prod 非空即拒绝启动；
     * 允许指向官方 LWA 主机或白名单主机。留空 = {@link #lwaEndpoint}。
     */
    private String lwaEndpointOverride;

    /**
     * 额外出站主机白名单（P0-51），对应环境变量 SPAPI_ALLOWLIST（逗号分隔）。
     * <p>
     * 默认策略只允许官方主机 + 回环地址（{@code 127.0.0.1} / {@code localhost} / {@code ::1}）；
     * 需要指向内网桩（如 {@code stub.internal.example.com}）时必须显式登记。
     * 判定为<b>精确匹配</b>（大小写与首尾空白不敏感），不做后缀/通配/DNS 解析。
     */
    private List<String> allowlist = new ArrayList<>();

    /**
     * 官方必填 user-agent 的应用名（P0-35），对应环境变量 SPAPI_APP_NAME。
     */
    private String appName = "AmazonERP";

    /**
     * 官方必填 user-agent 的应用版本（P0-35），对应环境变量 SPAPI_APP_VERSION。
     */
    private String appVersion = "1.0-SNAPSHOT";

    /**
     * 官方必填 user-agent 的语言属性（P0-35），默认取当前 JVM 版本。
     * 对应环境变量 SPAPI_LANGUAGE；留空则回退默认值（不得显式设为空白串）。
     */
    private String language = "Java/" + Runtime.version();

    /**
     * user-agent 的可选 Platform 属性（P0-35），对应环境变量 SPAPI_PLATFORM。
     */
    private String platform;

    /**
     * user-agent 整串覆盖（P0-35）：非空白时优先于上面四个字段。
     * 对应环境变量 SPAPI_USER_AGENT，供特殊合规要求下逐字指定。
     */
    private String userAgent;
}
