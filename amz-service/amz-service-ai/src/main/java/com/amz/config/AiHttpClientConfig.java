package com.amz.config;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * AI 模块共享 OkHttpClient。
 * <p>
 * OkHttp 客户端设计为进程内共享：每个客户端持有独立的连接池与线程池，
 * 旧实现中 AiServiceImpl / ReviewAnalysisServiceImpl / SelectionAnalysisServiceImpl
 * 各自 new 一个客户端，最多产生 3 套连接池。统一收敛到本 Bean 后复用连接与线程资源。
 * <p>
 * 超时口径与原实现一致：连接 30s / 读 60s / 写 30s（Agent 多轮工具调用下
 * 单轮 LLM 最长等待约 60s）。
 */
@Configuration
public class AiHttpClientConfig {

    @Bean
    public OkHttpClient aiOkHttpClient() {
        Dispatcher dispatcher = new Dispatcher();
        // Agent 单轮对话可能并发发起多个 LLM 请求（多用户场景），放宽默认 64 并发上限
        dispatcher.setMaxRequests(128);
        dispatcher.setMaxRequestsPerHost(64);

        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(16, 5, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .build();
    }
}
