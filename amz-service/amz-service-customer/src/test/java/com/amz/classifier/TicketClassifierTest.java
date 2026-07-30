package com.amz.classifier;

import com.amz.classifier.TicketClassifier.Classification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工单 AI 自动分类器单元测试（纯逻辑，无外部依赖）。
 * <p>
 * 验证关键词分类、情绪判定、优先级分配三条核心逻辑，
 * 覆盖物流、质量、退货、发票、愤怒、正面、空输入等场景。
 */
@DisplayName("工单分类器单元测试")
class TicketClassifierTest {

    private final TicketClassifier classifier = new TicketClassifier();

    @Test
    @DisplayName("物流关键词 → SHIPPING + NEUTRAL + NORMAL")
    void testShippingCategory() {
        Classification c = classifier.classify("Where is my package? I haven't received it yet.");

        assertEquals("SHIPPING", c.getCategory(), "应分类为物流");
        assertEquals("NEUTRAL", c.getSentiment(), "应为中性情绪");
        assertEquals("NORMAL", c.getPriority(), "优先级应为 NORMAL");
    }

    @Test
    @DisplayName("质量关键词 → PRODUCT_QUALITY + NEGATIVE + HIGH")
    void testProductQualityCategory() {
        Classification c = classifier.classify("The product is broken and defective. It doesn't work.");

        assertEquals("PRODUCT_QUALITY", c.getCategory(), "应分类为质量问题");
        assertEquals("NEGATIVE", c.getSentiment(), "应为负面情绪");
        assertEquals("HIGH", c.getPriority(), "优先级应为 HIGH");
    }

    @Test
    @DisplayName("退货关键词 → RETURN_REFUND + NEGATIVE + HIGH")
    void testReturnRefundCategory() {
        Classification c = classifier.classify("I want to return this and get a refund.");

        assertEquals("RETURN_REFUND", c.getCategory(), "应分类为退货退款");
        assertEquals("NEGATIVE", c.getSentiment());
        assertEquals("HIGH", c.getPriority());
    }

    @Test
    @DisplayName("发票关键词 → INVOICE + NEUTRAL + NORMAL")
    void testInvoiceCategory() {
        Classification c = classifier.classify("Could you send me a tax invoice for this order?");

        assertEquals("INVOICE", c.getCategory(), "应分类为发票");
        assertEquals("NEUTRAL", c.getSentiment());
        assertEquals("NORMAL", c.getPriority());
    }

    @Test
    @DisplayName("愤怒词 → URGENT 优先级（覆盖负面情绪）")
    void testAngrySentimentOverridesToUrgent() {
        Classification c = classifier.classify("This is terrible and unacceptable! I will report you!");

        assertEquals("ANGRY", c.getSentiment(), "应为愤怒情绪");
        assertEquals("URGENT", c.getPriority(), "愤怒词应提升至 URGENT");
    }

    @Test
    @DisplayName("愤怒词 + 物流关键词 → SHIPPING + ANGRY + URGENT")
    void testAngryWithShippingCategory() {
        Classification c = classifier.classify("Where is my package? This is a scam! Furious!");

        assertEquals("SHIPPING", c.getCategory(), "分类仍由物流关键词决定");
        assertEquals("ANGRY", c.getSentiment());
        assertEquals("URGENT", c.getPriority());
    }

    @Test
    @DisplayName("正面词 → POSITIVE + LOW")
    void testPositiveSentiment() {
        Classification c = classifier.classify("Great product! I love it, excellent quality.");

        assertEquals("POSITIVE", c.getSentiment(), "应为正面情绪");
        assertEquals("LOW", c.getPriority(), "正面情绪优先级应为 LOW");
    }

    @Test
    @DisplayName("空内容 → OTHER + NEUTRAL + LOW")
    void testEmptyContent() {
        Classification c = classifier.classify("");

        assertEquals("OTHER", c.getCategory());
        assertEquals("NEUTRAL", c.getSentiment());
        assertEquals("LOW", c.getPriority());
    }

    @Test
    @DisplayName("null 内容 → OTHER + NEUTRAL + LOW")
    void testNullContent() {
        Classification c = classifier.classify(null);

        assertEquals("OTHER", c.getCategory());
        assertEquals("NEUTRAL", c.getSentiment());
        assertEquals("LOW", c.getPriority());
    }

    @Test
    @DisplayName("无匹配关键词 → OTHER + NEUTRAL + NORMAL")
    void testNoMatchCategory() {
        Classification c = classifier.classify("Hello, I have a general question about your store.");

        assertEquals("OTHER", c.getCategory());
        assertEquals("NEUTRAL", c.getSentiment());
        assertEquals("NORMAL", c.getPriority());
    }
}
