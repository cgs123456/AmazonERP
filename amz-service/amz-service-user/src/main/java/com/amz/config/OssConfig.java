package com.amz.config;

import com.amz.util.OssUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * oss配置
 */
@Configuration
public class OssConfig {

    @Value("${oss.endpoint}")
    private String endpoint;

    @Value("${oss.accessKeyId}")
    private String accessKeyId;

    @Value("${oss.accessKeySecret}")
    private String accessKeySecret;

    @Value("${oss.bucketName}")
    private String bucketName;

    @Bean
    public OssUtil ossUtil() {
        OssUtil ossUtil = new OssUtil();
        ossUtil.setAccessKeyId(accessKeyId);
        ossUtil.setAccessKeySecret(accessKeySecret);
        ossUtil.setBucketName(bucketName);
        ossUtil.setEndpoint(endpoint);
        return ossUtil;
    }
}
