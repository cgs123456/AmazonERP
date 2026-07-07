package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 客服工单微服务启动类。
 * 负责多店铺统一消息中心、AI 自动分类、索评助手。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceCustomerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmzServiceCustomerApplication.class, args);
    }
}
