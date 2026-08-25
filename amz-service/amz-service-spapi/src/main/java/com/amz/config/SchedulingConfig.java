package com.amz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * SP-API 模块调度线程池。
 * <p>
 * Spring 的 {@code @Scheduled} 默认共享单线程调度器：订单同步（限流退避时可达数分钟）
 * 会饿死库存同步与补货重算。显式声明多线程调度器后各任务并行互不阻塞；
 * 配合 {@code DistributedJobLock}，多实例部署下同一任务仍全局互斥。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 订单同步 / 库存同步 / 补货重算 三个任务 + 余量
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("amz-spapi-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
