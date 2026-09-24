package com.amz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
