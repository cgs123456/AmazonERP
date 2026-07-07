package com.amz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Amazon ERP AI / Agent 微服务启动类。
 * <p>
 * 集成能力：
 * <ul>
 *   <li>基础 LLM 对话（DeepSeek）</li>
 *   <li>ERP Agent 编排（Function Calling）</li>
 *   <li>用户偏好记忆 + 主动提醒</li>
 *   <li>每日昨日运营报告定时推送</li>
 *   <li>多语言对话（中/英/日/德）</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients
@MapperScan("com.amz.mapper")
public class AmzServiceAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmzServiceAiApplication.class, args);
    }

}
