package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 业财一体化微服务启动类。
 * 负责金蝶对接、自动凭证生成、多币种核算。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceFinanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmzServiceFinanceApplication.class, args);
    }
}
