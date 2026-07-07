package com.amz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class AmzServiceAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmzServiceAiApplication.class, args);
    }

}
