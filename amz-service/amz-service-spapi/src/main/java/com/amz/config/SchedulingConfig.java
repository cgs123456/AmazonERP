package com.amz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * SP-API 模块调度线程池。
 * <p>
 * Spring 的 {@code @Scheduled} 默认共享单线程调度器：订单同步（限流退避时可达数分钟）
 * 会饿死库存同步与补货重算。显式声明多线程调度器后各任务并行互不阻塞；
 * 配合 {@code DistributedJobLock}，多实例部署下同一任务仍全局互斥。
 */
@Configuration
@Profile("!bootstrap")
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 订单同步 / 库存同步 / 补货重算 / Outbox 重放 四个任务
        // + 通知 Inbox Worker / 租约恢复 / 订阅对账 三个任务 + 余量
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("amz-spapi-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
