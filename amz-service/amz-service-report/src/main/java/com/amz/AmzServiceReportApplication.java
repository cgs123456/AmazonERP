package com.amz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 数据报表微服务启动类。
 * 负责多维度可视化报表：流量、转化率、退货率、销售额趋势等。
 * 通过 Feign 聚合各业务微服务数据，生成可视化所需的数据结构。
 */
@SpringBootApplication
@EnableFeignClients
public class AmzServiceReportApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmzServiceReportApplication.class, args);
    }
}
