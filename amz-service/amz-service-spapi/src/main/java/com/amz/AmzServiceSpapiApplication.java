package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Amazon SP-API 集成微服务启动类。
 * 负责订单同步、店铺凭证管理、AWS Sig V4 签名等能力。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceSpapiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmzServiceSpapiApplication.class, args);
    }
}
