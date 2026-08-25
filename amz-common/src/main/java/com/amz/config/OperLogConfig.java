package com.amz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 操作日志审计配置：启用 {@code @Async} 以支持 {@code OperLogAspect} 异步落盘，
 * 避免日志写入阻塞业务线程。
 * <p>
 * 显式声明默认任务执行器：若无任何 TaskExecutor Bean，
 * Spring 的 {@code @Async} 会退化为 {@code SimpleAsyncTaskExecutor}——
 * 每个任务新建线程且无上限，高并发下存在线程爆炸风险。
 * <p>
 * Bean 名使用 {@code amzAsyncExecutor} 而非 {@code taskExecutor}：
 * 个别业务模块（如 product）自带名为 taskExecutor 的执行器，
 * 同名会导致 BeanDefinitionOverrideException 启动失败。
 * {@code @Async} 默认执行器解析顺序：先按名 taskExecutor，再按类型唯一候选，
 * 因此本 Bean 在未自定义执行器的服务中生效，在已有执行器的服务中自动让位。
 */
@Configuration
@EnableAsync
public class OperLogConfig {

    @Bean
    public ThreadPoolTaskExecutor amzAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("amz-async-");
        // 队列满时由调用线程执行：异步任务不丢失，仅临时降速
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
