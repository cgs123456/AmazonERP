package com.amz.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link KnowledgeEsClient} 的构造<b>不得</b>触碰网络栈。
 * <p>
 * <b>为什么需要它</b>（2026-10-19 实测）：本类是 {@code @Component}，原实现在
 * <b>字段初始化</b>里 {@code HttpClient.newBuilder().build()}。于是启动时的依赖链
 * {@code erpAnalysisTools → knowledgeSearchService → hybridSearchService → esClient}
 * 会在最底下断掉，整个 AI 服务起不来——而知识库检索是<b>可选能力</b>，
 * 连不上 ES 本应只影响该功能，不该决定进程存亡。
 * <p>
 * <b>断言行为而不是实现</b>：不检查字段修饰符、不检查构造器源码，只断言
 * 「在任何环境下构造它都不会抛异常」。换成别的惰性写法同样应该通过。
 */
@DisplayName("KnowledgeEsClient：构造不碰网络栈（知识库检索是可选能力）")
class KnowledgeEsClientStartupIsolationTest {

    @Test
    @DisplayName("无网络环境下也能构造（不能因为 ES 不可达就让 AI 服务起不来）")
    void constructionDoesNotOpenNetworkResources() {
        assertDoesNotThrow(KnowledgeEsClient::new,
                "构造 KnowledgeEsClient 不应触碰网络栈——"
                        + "本类在启动依赖链上，一旦构造期建 selector，"
                        + "ES 不可达会连带整个 AI 服务起不来");
    }
}
