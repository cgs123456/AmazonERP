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

    /**
     * 审计落库专用执行器：与 {@link #amzAsyncExecutor()} 分开，避免两类相互拖累。
     * <p>
     * 分开的三个原因：
     * <ol>
     *   <li><b>拒绝策略必须不同</b>：{@code amzAsyncExecutor} 用 CallerRunsPolicy
     *       （任务不丢失，宁可降速）。但审计落库是 DB 写，比纯文件 IO 慢一个量级，
     *       队列满时让业务线程自己去写库会直接把接口响应时间拖垮——
     *       <b>审计让位于业务</b>，故本池用 AbortPolicy：队列满即丢弃并计数。</li>
     *   <li><b>product 服务的 taskExecutor 抢占</b>：该模块自命名 taskExecutor
     *       （core 2 / max 5），默认 {@code @Async} 会落到这个 5 线程的池，
     *       被 listing 复制这类长任务挤占。审计必须显式按名引用本 Bean 才躲得开。</li>
     *   <li><b>可独立观测</b>：审计写失败/丢弃需要单独计数告警，混在通用池里看不出来。</li>
     * </ol>
     * 丢弃是可接受的：审计是旁路留痕，业务成功但审计丢失 ≠ 业务数据错误，
     * 且 oper-log.log 文件仍在（切面先写文件再写库，两者独立）。
     */
    @Bean
    public ThreadPoolTaskExecutor amzAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("amz-audit-");
        // 队列满即丢弃（由切面捕获 RejectedExecutionException 并计数），绝不回压业务线程
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
