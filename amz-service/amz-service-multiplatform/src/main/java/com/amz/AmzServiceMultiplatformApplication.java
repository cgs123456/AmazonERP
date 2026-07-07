package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多平台对接微服务启动类。
 * 聚合 Temu / TikTok Shop / Shein 等平台订单，统一管理。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceMultiplatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmzServiceMultiplatformApplication.class, args);
    }
}
