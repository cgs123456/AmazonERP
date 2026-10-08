package com.amz.agent.review;

import com.amz.exception.CodeErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评论分析缺 DeepSeek key 必须点名失败，不能返回「0 分 + 空列表」的假成功
 * （2026-10-08 修复的 B 桶缺陷）。
 * <p>
 * <b>实测发现的缺陷</b>：{@code callDeepSeek} 在 apiKey 空时返回 {@code null}，
 * {@code parseResult(null)} 合成 {@code sentimentScore=0.0} + 空 painPoints/suggestions
 * + 文案「LLM 分析失败，未返回结果」，controller 仍 {@code Result.success} ——
 * HTTP 200 下 0.0 会被读成「中性情绪」、空列表读成「没有痛点」，是典型假成功
 * （本仓反复强调「显式拒绝优于假成功」）。
 * <p>
 * 修法：LLM 不可用/无响应时抛 {@link CodeErrorException}，经全局处理器转点名失败，
 * 与 {@code AiServiceImpl}「DeepSeek 未配置：请设置 DEEPSEEK_API_KEY」同一口径。
 */
@DisplayName("评论分析：缺 DeepSeek key 必须点名失败")
class ReviewAnalysisFailClosedTest {

    private final ReviewAnalysisServiceImpl service = new ReviewAnalysisServiceImpl();

    private ReviewInfo review() {
        ReviewInfo r = new ReviewInfo();
        r.setRating(2);
        r.setTitle("不好用");
        r.setContent("很快就坏了");
        r.setDate("2026-09-01");
        r.setVerifiedPurchase(true);
        return r;
    }

    @Test
    @DisplayName("apiKey 为空 + 有评论 → 抛业务异常，不返回 0 分假结果")
    void missingApiKeyFailsClosed() {
        ReflectionTestUtils.setField(service, "apiKey", "");

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.analyze(List.of(review())),
                "缺 key 时不得返回合成结果（0 分会被读成中性情绪、空列表读成没有痛点）");

        assertTrue(ex.getMessage().contains("DEEPSEEK_API_KEY"),
                "失败文案要点名缺哪个配置，否则运维不知道要补什么：" + ex.getMessage());
    }

    @Test
    @DisplayName("apiKey 为 null 同样点名失败（与空串一致）")
    void nullApiKeyFailsClosed() {
        ReflectionTestUtils.setField(service, "apiKey", null);

        assertThrows(CodeErrorException.class, () -> service.analyze(List.of(review())));
    }

    @Test
    @DisplayName("空评论列表仍是合法空态（不是缺配置，不该报错）")
    void emptyReviewsIsLegitEmptyNotFailure() {
        ReflectionTestUtils.setField(service, "apiKey", "");

        ReviewAnalysisResult result = service.analyze(List.of());

        // 空输入在调用 LLM 之前就返回，与「缺 key」是两回事：这是真实的「没有评论可分析」
        assertTrue(result.getSummary().contains("无评论数据"),
                "空列表应给出「无数据」而非抛错：" + result.getSummary());
    }
}
