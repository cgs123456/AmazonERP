package com.amz.client;

import com.amz.credential.AdvertisingCredentialProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link AdvertisingApiRealClient} 的<b>生产构造器</b>不得触碰网络栈。
 * <p>
 * <b>为什么需要它</b>（2026-10-19 实测）：本类是 {@code @Component @Profile("!mock")}，
 * 生产档必然实例化。原实现把 {@code defaultHttpClient()} 放进
 * {@code @Autowired} 构造器的参数里，于是「JVM 能否打开 selector」决定 ad 服务能否启动——
 * 而广告调用<b>本就是可失败降级</b>的：缺凭证时 fail-closed 抛
 * {@code AD_CREDENTIALS_NOT_CONFIGURED}，那是业务行为，不该升级成进程起不来。
 * <p>
 * <b>注意与契约测试的分工</b>：
 * {@code AdvertisingApiRealClientContractTest} 在本机因它自己调用
 * {@code HttpClient.newHttpClient()} 而整体报 loopback 错（属已知环境白名单）。
 * 本测试<b>刻意不碰那个</b>——它只验证<b>我们自己的生产构造器</b>（传 null 表示按需自建）
 * 在任何环境下都能构造成功，两条测试互不遮挡。
 * <p>
 * <b>断言行为而不是实现</b>：不检查字段修饰符、不检查构造器源码。
 */
@DisplayName("AdvertisingApiRealClient：生产构造器不碰网络栈（广告是可降级能力）")
class AdvertisingApiRealClientStartupIsolationTest {

    /** 无凭证的 provider：本测试只走到构造阶段，不需要任何真实凭证。 */
    private static final AdvertisingCredentialProvider EMPTY_PROVIDER = shopId -> Optional.empty();

    @Test
    @DisplayName("生产构造器在无网络环境下也能构造（ad 服务不能因此起不来）")
    void productionConstructorDoesNotOpenNetworkResources() {
        assertDoesNotThrow(() -> new AdvertisingApiRealClient(EMPTY_PROVIDER),
                "生产构造器不应触碰网络栈——本类在生产档（@Profile(\"!mock\")）必然实例化，"
                        + "一旦构造期建 selector，ad 服务在无网络环境下就起不来");
    }
}
