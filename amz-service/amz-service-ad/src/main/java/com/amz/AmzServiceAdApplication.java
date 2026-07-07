package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Amazon 广告管理微服务启动类。
 * 负责广告数据分析（ACoS/ROAS）、分时调价、关键词优化建议。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceAdApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmzServiceAdApplication.class, args);
    }
}
