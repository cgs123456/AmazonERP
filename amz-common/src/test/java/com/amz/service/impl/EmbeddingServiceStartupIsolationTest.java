package com.amz.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link EmbeddingServiceImpl} 的构造**不得依赖运行环境的网络栈**。
 * <p>
 * <b>这条测试守的是一个真实的上线级故障</b>（2026-10-19 实测）：
 * 该类位于 {@code amz-common}，被全部 17 个服务依赖并扫描 {@code com.amz} 包，
 * 包括 gateway 与 message 这些<b>根本不做 embedding</b> 的模块。原实现把
 * {@code HttpClient} 写成 {@code static final} 字段初始化，于是：
 * <pre>
 * new HttpClient() → Selector.open() → AF_UNIX loopback → SocketException
 *   → ExceptionInInitializerError → Bean 创建失败 → 整个 Spring 上下文起不来
 * </pre>
 * gateway 冒烟因此直接崩在 {@code embeddingServiceImpl}，而 embedding 默认
 * {@code embedding.enabled=false}——一个**关闭着的可选能力**拖垮了启动。
 * <p>
 * <b>为什么用「构造不抛异常」而不是「字段不是 static final」来断言</b>：
 * 前者描述的是可观察行为（能在任何环境跑），后者只是在钉实现细节。
 * 把 {@code HttpClient} 换成别的惰性方案同样应该通过。
 * <p>
 * 注意：本机（AF_UNIX 被拒）修前必红、修后必绿；CI（Linux，selector 正常）
 * 修前也是绿的——所以**这条测试在本机有诊断价值，在 CI 上主要用于防回归**。
 */
@DisplayName("EmbeddingServiceImpl：构造不碰网络栈（可选能力不得拖垮启动）")
class EmbeddingServiceStartupIsolationTest {

    @Test
    @DisplayName("无网络环境下也能构造；未启用时 isAvailable 为 false")
    void constructionDoesNotOpenNetworkResources() {
        EmbeddingServiceImpl service = assertDoesNotThrow(EmbeddingServiceImpl::new,
                "构造 EmbeddingServiceImpl 不应触碰网络栈——"
                        + "本类被所有服务扫描，一旦依赖 selector 就会让不使用 embedding 的服务也起不来");
        // 默认 enabled=false、apiKey 为空 → 必须明确不可用，而不是「构造成功但行为不明」
        assertFalse(service.isAvailable(), "默认配置下 embedding 必须不可用");
    }
}
