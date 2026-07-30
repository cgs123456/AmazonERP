package com.amz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication
public class AmzNettyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmzNettyApplication.class, args);
    }

}