package com.amz.optimizer;

import com.amz.analytics.AdPerformanceAnalyzer;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 关键词优化器：基于 ACoS/CR/CTR 给出调价或暂停建议。
 * <p>
 * 优化策略（参考领星/船长 BI）：
 * <ul>
 *   <li>高 ACoS + 低 CR（转化差）→ 暂停关键词（PAUSE）</li>
 *   <li>高 ACoS + 高 CR（转化好但成本高）→ 降价 20%（DECREASE_BID）</li>
 *   <li>低 ACoS + 高 CR（优质词）→ 加价 30%（INCREASE_BID）抢占排名</li>
 *   <li>高曝光 + 零点击（CTR 极低）→ 否词（NEGATIVE）</li>
 *   <li>数据不足 → 观察不动（OBSERVE）</li>
 * </ul>
 */
@Component
public class KeywordOptimizer {

    /** 阈值常量 */
    private static final BigDecimal HIGH_ACOS = new BigDecimal("40");
    private static final BigDecimal LOW_ACOS = new BigDecimal("15");
    private static final BigDecimal HIGH_CR = new BigDecimal("10");
    private static final BigDecimal LOW_CR = new BigDecimal("3");
    private static final long HIGH_IMPRESSION_ZERO_CLICK = 1000L;

    @Autowired
    private AdPerformanceAnalyzer analyzer;

    /**
     * 优化建议。
     */
    @Data
    public static class Suggestion {
        private String keyword;
        private String action;          // PAUSE / DECREASE_BID / INCREASE_BID / NEGATIVE / OBSERVE
        private BigDecimal currentBid;
        private BigDecimal suggestedBid;
        private String reason;
    }

    /**
     * 对一批关键词报表生成优化建议。
     *
     * @param keywords 关键词基础信息
     * @param reports  对应的关键词级报表（按 keyword 关联）
     */
    public List<Suggestion> optimize(List<AdKeyword> keywords, List<AdReport> reports) {
        List<Suggestion> suggestions = new ArrayList<>();
        if (keywords == null || reports == null) {
            return suggestions;
        }
        // 先填充派生指标
        analyzer.analyzeAll(reports);
        for (AdKeyword kw : keywords) {
            AdReport r = reports.stream()
                    .filter(x -> x.getKeyword() != null && x.getKeyword().equalsIgnoreCase(kw.getKeyword()))
                    .findFirst()
                    .orElse(null);
            suggestions.add(buildSuggestion(kw, r));
        }
        return suggestions;
    }

    private Suggestion buildSuggestion(AdKeyword kw, AdReport r) {
        Suggestion s = new Suggestion();
        s.setKeyword(kw.getKeyword());
        s.setCurrentBid(kw.getBid());

        if (r == null || r.getAcos() == null) {
            s.setAction("OBSERVE");
            s.setReason("数据不足，持续观察");
            return s;
        }

        BigDecimal acos = r.getAcos();
        BigDecimal cr = r.getCr() != null ? r.getCr() : BigDecimal.ZERO;

        // 高曝光零点击 → 否词
        if (r.getClicks() != null && r.getClicks() == 0
                && r.getImpressions() != null && r.getImpressions() > HIGH_IMPRESSION_ZERO_CLICK) {
            s.setAction("NEGATIVE");
            s.setReason("高曝光零点击，CTR 极低，建议精准否定");
            return s;
        }

        // 高 ACoS
        if (acos.compareTo(HIGH_ACOS) >= 0) {
            if (cr.compareTo(LOW_CR) < 0) {
                s.setAction("PAUSE");
                s.setReason("ACoS=" + acos + "% 且 CR=" + cr + "%，转化极差，建议暂停");
            } else {
                s.setAction("DECREASE_BID");
                s.setSuggestedBid(kw.getBid().multiply(new BigDecimal("0.8")));
                s.setReason("ACoS=" + acos + "% 偏高但有转化，降价 20% 控成本");
            }
            return s;
        }

        // 低 ACoS + 高 CR → 加价
        if (acos.compareTo(LOW_ACOS) < 0 && cr.compareTo(HIGH_CR) >= 0) {
            s.setAction("INCREASE_BID");
            s.setSuggestedBid(kw.getBid().multiply(new BigDecimal("1.3")));
            s.setReason("ACoS=" + acos + "% CR=" + cr + "%，优质词加价 30% 抢排名");
            return s;
        }

        s.setAction("OBSERVE");
        s.setReason("ACoS=" + acos + "% CR=" + cr + "%，表现平稳，持续观察");
        return s;
    }
}
