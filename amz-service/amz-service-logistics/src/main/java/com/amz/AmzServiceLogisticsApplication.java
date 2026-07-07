package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 物流追踪微服务启动类。
 * 负责头程物流、FBA 货件管理、轨迹可视化。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceLogisticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmzServiceLogisticsApplication.class, args);
    }
}
