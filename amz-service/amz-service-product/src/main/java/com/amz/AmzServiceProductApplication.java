package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.amz.mapper")
public class AmzServiceProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmzServiceProductApplication.class, args);
    }

}
