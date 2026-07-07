package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 采购供应链微服务启动类。
 * 负责 1688 采购下单、生产跟踪、质检流程、入库对账。
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceProcurementApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmzServiceProcurementApplication.class, args);
    }
}
