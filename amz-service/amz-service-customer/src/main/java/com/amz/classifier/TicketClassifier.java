package com.amz.classifier;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 工单 AI 自动分类器。
 * <p>
 * 生产环境应调用 LLM（通过 amz-service-ai 的 Feign）做意图识别 + 情感分析。
 * 当前为基于关键词规则的离线实现，覆盖 5 大常见类目 + 4 级情绪判定，保证可独立运行。
 * <p>
 * 分类逻辑（参考领星客服中心）：
 * <ul>
 *   <li>SHIPPING：物流关键词（shipping / delivery / tracking / where is my package）</li>
 *   <li>PRODUCT_QUALITY：质量关键词（broken / defective / damaged / quality）</li>
 *   <li>RETURN_REFUND：退货退款（return / refund / money back / cancel）</li>
 *   <li>INVOICE：发票（invoice / receipt / tax）</li>
 *   <li>OTHER：兜底</li>
 * </ul>
 * 优先级：含愤怒词（angry / terrible / lawyer / report）→ URGENT；负面情绪 → HIGH；中性 → NORMAL。
 */
@Component
public class TicketClassifier {

    private static final Set<String> SHIPPING_KW = Set.of(
            "shipping", "delivery", "tracking", "where is my package", "haven't received", "not arrived");
    private static final Set<String> QUALITY_KW = Set.of(
            "broken", "defective", "damaged", "quality", "doesn't work", "stopped working");
    private static final Set<String> RETURN_KW = Set.of(
            "return", "refund", "money back", "cancel", "cancel order");
    private static final Set<String> INVOICE_KW = Set.of(
            "invoice", "receipt", "tax invoice");
    private static final Set<String> ANGRY_KW = Set.of(
            "angry", "terrible", "lawyer", "report", "scam", "worst", "furious", "unacceptable");
    private static final Set<String> POSITIVE_KW = Set.of(
            "great", "love", "excellent", "amazing", "thank", "perfect");

    @Data
    public static class Classification {
        private String category;
        private String priority;
        private String sentiment;
    }

    /**
     * 对消息内容做分类。
     *
     * @param content 买家原始消息
     */
    public Classification classify(String content) {
        Classification c = new Classification();
        if (content == null || content.isBlank()) {
            c.setCategory("OTHER");
            c.setPriority("LOW");
            c.setSentiment("NEUTRAL");
            return c;
        }
        String lower = content.toLowerCase(Locale.ROOT);

        // 分类
        if (containsAny(lower, SHIPPING_KW)) {
            c.setCategory("SHIPPING");
        } else if (containsAny(lower, QUALITY_KW)) {
            c.setCategory("PRODUCT_QUALITY");
        } else if (containsAny(lower, RETURN_KW)) {
            c.setCategory("RETURN_REFUND");
        } else if (containsAny(lower, INVOICE_KW)) {
            c.setCategory("INVOICE");
        } else {
            c.setCategory("OTHER");
        }

        // 情绪 + 优先级
        if (containsAny(lower, ANGRY_KW)) {
            c.setSentiment("ANGRY");
            c.setPriority("URGENT");
        } else if (containsAny(lower, POSITIVE_KW)) {
            c.setSentiment("POSITIVE");
            c.setPriority("LOW");
        } else if (containsAny(lower, RETURN_KW) || containsAny(lower, QUALITY_KW)) {
            c.setSentiment("NEGATIVE");
            c.setPriority("HIGH");
        } else {
            c.setSentiment("NEUTRAL");
            c.setPriority("NORMAL");
        }
        return c;
    }

    private boolean containsAny(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
