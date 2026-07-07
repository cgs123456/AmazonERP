package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运营工具微服务启动类。
 * 负责差评监控、跟卖监控、关键词排名追踪。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmzServiceOpsApplication.class, args);
    }
}
