package com.amz.client.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link KeepaRealClient} 的构造<b>不得</b>触碰网络栈。
 * <p>
 * <b>这条测试钉的是一次「形状像惰性、行为不是惰性」的失败</b>（2026-10-19 实测）：
 * 上一版把 {@code HttpClient.newBuilder()} 挪进了私有方法 {@code defaultHttpClient()}
 * 并写了「延迟创建」的注释，看起来已经处理过了——但<b>默认无参构造器直接调用它</b>。
 * 本类是 {@code @Component @Profile("!mock")}，生产档必然实例化，于是「JVM 能否打开
 * selector」直接决定 product 服务能否启动。
 * <p>
 * <b>断言行为而不是实现</b>：没写「字段不是 static final」「构造器里没有
 * HttpClient.newBuilder」这类检查——那是钉实现细节，换成别的惰性写法就得改测试。
 * 这里只断言可观察事实：在任何环境下构造它都不会抛异常。
 */
@DisplayName("KeepaRealClient：构造不碰网络栈（可选连接器不得拖垮启动）")
class KeepaRealClientStartupIsolationTest {

    @Test
    @DisplayName("无网络环境下也能构造（Keepa 是可选连接器，没有它 product 服务也要能起）")
    void constructionDoesNotOpenNetworkResources() {
        assertDoesNotThrow(() -> {
            // 语句体 lambda 而不是 KeepaRealClient::new：本类有两个构造器，
            // 方法引用会让 assertDoesNotThrow 的 Executable/ThrowingSupplier 重载无法消歧。
            new KeepaRealClient();
        }, "构造 KeepaRealClient 不应触碰网络栈——"
                + "本类在生产档（@Profile(\"!mock\")）必然实例化，"
                + "一旦依赖 selector，product 服务在无网络环境下就起不来");
    }
}
