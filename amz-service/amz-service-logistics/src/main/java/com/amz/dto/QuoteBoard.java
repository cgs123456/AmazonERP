package com.amz.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物流商比价看板。
 * <p>
 * 回答的问题：<b>我手上的运价里，哪些还能用？哪条航线只有一家承运商（没有议价空间）？</b>
 * <p>
 * 之所以要把「过期但状态还没改」单独计数，是因为报价失效靠人工或定时任务置位，
 * 中间必然存在一段「已经不能用了但看起来还是 ACTIVE」的窗口，
 * 这段窗口里做出来的选商结论是错的，必须能被看见。
 */
@Data
public class QuoteBoard implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long shopId;

    /** 本店报价总数（含已过期 / 已停用） */
    private int totalQuotes;

    /** 当前有效：状态 ACTIVE 且未过失效日期 */
    private int validQuotes;

    /** 已过失效日期但状态仍为 ACTIVE —— 待收口，比价时会自动排除 */
    private int staleByDate;

    /** 30 天内即将失效，需要提前重新询价 */
    private int expiringIn30Days;

    /** 已置为 EXPIRED / DISABLED */
    private int inactiveQuotes;

    /** 两个单价都为空，无法参与比价（数据质量问题） */
    private int unpricedQuotes;

    private int carrierCount;

    private int routeCount;

    /** 运输方式 → 有效报价数 */
    private Map<String, Integer> byServiceType;

    /** 币种 → 有效报价数；出现多个币种时无法直接比价 */
    private Map<String, Integer> byCurrency;

    /** 航线覆盖，按承运商数升序：单一来源的航线排最前，最需要补供应商 */
    private List<RouteCoverage> routes;

    /** 数据质量与口径提示，前端直接展示，不需要再判断 */
    private List<String> warnings;

    /** 单条航线的报价覆盖度 */
    @Data
    public static class RouteCoverage implements Serializable {

        private static final long serialVersionUID = 1L;

        private String originPort;
        private String destinationPort;

        /** 该航线上有有效报价的承运商数（去重） */
        private int carrierCount;

        private List<String> carriers;

        /** 最短运输天数；-1 表示该航线均未填写 */
        private int minTransitDays;

        /** 最长运输天数；-1 表示该航线均未填写 */
        private int maxTransitDays;

        private BigDecimal minPricePerKg;
        private BigDecimal maxPricePerKg;
        private BigDecimal minPricePerCbm;
        private BigDecimal maxPricePerCbm;

        /** 统一币种；多币种时为 MIXED，此时价格区间仅供参考 */
        private String currency;

        /** 承运商不足两家：无议价空间，属于供应链单点风险 */
        private boolean singleSource;

        /** 最高价相对最低价的溢价率（同币种且有可比单价时给出，否则 null） */
        private BigDecimal priceSpreadRate;
    }
}
