package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回款概览。
 * <p>
 * 关键口径：<b>「在途未回」与「已回但短款」是两个独立字段，不可相加也不可互抵</b>。
 * 前者是还没到账的钱（等待即可），后者是已经到账但少了的钱（需要索赔动作）——
 * 合并成一个「差额」会同时抹掉这两种完全不同的处置路径。
 */
@Data
public class PaymentCollectionSummary {

    private Long shopId;

    private int totalOrders;
    private int pendingOrders;
    private int inTransitOrders;
    private int settledOrders;
    private int refundedOrders;
    private int shortfallOrders;

    /** 应收合计。 */
    private BigDecimal receivableTotal = BigDecimal.ZERO;
    /** 实收合计。 */
    private BigDecimal netReceivedTotal = BigDecimal.ZERO;
    /** 在途未回金额（已结算未到账部分）。 */
    private BigDecimal inTransitAmount = BigDecimal.ZERO;
    /** 已回金额。 */
    private BigDecimal settledAmount = BigDecimal.ZERO;
    /** 已回但短款金额（需索赔动作）。 */
    private BigDecimal shortfallAmount = BigDecimal.ZERO;

    /** 涉及币种（多币种时不跨币种求和，各自列示）。 */
    private Set<String> currencies = new LinkedHashSet<>();

    /** 口径提示（多币种、短款数据缺失等）。 */
    private List<String> warnings = new ArrayList<>();

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
}
