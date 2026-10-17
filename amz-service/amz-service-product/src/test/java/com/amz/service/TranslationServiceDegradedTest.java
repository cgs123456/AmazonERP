package com.amz.service;

import com.amz.mapper.TranslationCacheMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 翻译降级标记必须真实反映「LLM 调用失败」。
 *
 * <p><b>为什么这条测试必须存在</b>：{@code translate()} 在 LLM 不可用时返回原文，
 * 返回值上与「翻译后恰好相同」无法区分。为此新增了
 * {@code translateWithOutcome(...).degraded()}。但 degraded 这个布尔值一旦被写死，
 * 上层所有「按 degraded 决定是否中止」的逻辑都会失效，而
 * **下游 ListingCopyService 的测试仍然全绿**——它 mock 的是翻译服务，
 * 测的是「拿到 degraded 之后怎么做」，测不到「degraded 本身对不对」。
 *
 * <p><b>为什么用子类注入失败</b>：本机 loopback 建连失败（§S30 已定案不修），
 * 没法靠真实网络制造「LLM 不可用」。子类把 {@code callDeepSeek} 换成必然抛异常，
 * 测的仍是**真实的 translateWithOutcome 逻辑**——缓存未命中 → 调 LLM → 抛异常 → 降级。
 */
@DisplayName("TranslationService：降级标记必须真实")
class TranslationServiceDegradedTest {

    /** 把 LLM 调用换成必然失败；其余逻辑全部走真实实现。 */
    private static final class FailingLlmTranslationService extends TranslationService {
        @Override
        protected String callDeepSeek(String sourceText, String sourceLang, String targetLang) {
            throw new IllegalStateException(new IOException("simulated LLM outage"));
        }
    }

    private TranslationService newService() {
        TranslationService service = new FailingLlmTranslationService();
        // 三级缓存全部置空：必须真的走到 callDeepSeek
        ReflectionTestUtils.setField(service, "translationCacheMapper",
                mock(TranslationCacheMapper.class));
        return service;
    }

    @Test
    @DisplayName("LLM 不可用时 degraded=true，且 text 是原文")
    void llmFailureMarksDegraded() {
        TranslationService service = newService();

        TranslationService.TranslationOutcome outcome =
                service.translateWithOutcome("Hello world", "en", "de");

        assertTrue(outcome.degraded(), "LLM 失败时必须标记为降级");
        assertTrue("Hello world".equals(outcome.text()), "降级时返回原文，实际：" + outcome.text());
    }

    @Test
    @DisplayName("空文本不算降级——它本来就不需要翻译")
    void emptyTextIsNotDegraded() {
        TranslationService service = newService();

        TranslationService.TranslationOutcome outcome = service.translateWithOutcome("   ", "en", "de");

        assertFalse(outcome.degraded(), "空文本没有「翻译失败」这回事");
    }

    @Test
    @DisplayName("旧入口 translate() 仍返回原文，不因新方法而改变行为")
    void legacyTranslateStillReturnsText() {
        TranslationService service = newService();

        String text = service.translate("Hello world", "en", "de");

        assertTrue("Hello world".equals(text), "旧入口必须保持「拿不到译文就给原文」，实际：" + text);
    }
}
