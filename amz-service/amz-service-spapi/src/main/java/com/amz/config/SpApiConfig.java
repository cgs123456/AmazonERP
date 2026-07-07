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
}
