package com.amz.service;

import com.amz.mapper.TranslationCacheMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 翻译 L3 落库失败不得吞掉已经拿到的译文。
 * <p>
 * 存在动因（2026-10-10 实测）：{@code translate()} 把「调用 LLM」和「写回 L3」放在同一个
 * {@code try} 里，共用一个 {@code catch (Exception) { return sourceText; }}。
 * 于是 L3 {@code insert} 失败（最典型的是并发下同一 hash 撞唯一键
 * {@code uk_hash_langs}，抛 {@code DuplicateKeyException}）会被当成「LLM 调用失败」，
 * 把**刚刚成功拿到的译文丢掉、返回原文**。用户为一次 LLM 调用付了钱，却拿到未翻译的文本；
 * 表现是「同一个请求有时翻译、有时原样返回」，且不会有任何错误提示。
 * <p>
 * 缓存是加速层，不是正确性的一部分：写缓存失败只该降级为「本次不缓存」，
 * 不该降级为「本次不翻译」。
 */
@DisplayName("翻译 L3 写回失败：已成功的译文不得被丢弃")
class TranslationCacheWriteFailureTest {

    private TranslationCacheMapper mapper;

    /** 桩：LLM 恒返回固定译文，把被测点收敛到「写回失败」这一条路径上。 */
    private static class StubTranslationService extends TranslationService {
        @Override
        String callDeepSeek(String sourceText, String sourceLang, String targetLang) {
            return "TRANSLATED:" + sourceText;
        }
    }

    @BeforeEach
    void setUp() {
        mapper = mock(TranslationCacheMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
    }

    private TranslationService serviceWithMapper() {
        TranslationService service = new StubTranslationService();
        ReflectionTestUtils.setField(service, "translationCacheMapper", mapper);
        return service;
    }

    @Test
    @DisplayName("L3 撞唯一键（并发重复翻译）时仍返回译文，而不是退回原文")
    void duplicateKeyOnL3InsertStillReturnsTranslation() {
        when(mapper.insert(any(com.amz.model.TranslationCache.class)))
                .thenThrow(new DuplicateKeyException("uk_hash_langs"));
        TranslationService service = serviceWithMapper();

        String result = service.translate("hello world", "en", "de");

        assertEquals("TRANSLATED:hello world", result,
                "LLM 已经成功返回译文，L3 落库失败只应降级为「本次不缓存」，不能连译文一起丢掉");
    }

    @Test
    @DisplayName("L3 写入任意异常（如 DB 不可用）时仍返回译文")
    void l3InsertFailureStillReturnsTranslation() {
        when(mapper.insert(any(com.amz.model.TranslationCache.class)))
                .thenThrow(new RuntimeException("DB down"));
        TranslationService service = serviceWithMapper();

        String result = service.translate("second text", "en", "fr");

        assertEquals("TRANSLATED:second text", result, "写缓存失败不得改变翻译结果本身");
    }
}