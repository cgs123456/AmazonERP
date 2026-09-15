package com.amz.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * FBA 签收差异看板。
 * <p>
 * 回答的问题：<b>货发出去 1000 件、FBA 只签收 960 件，这些差额有多少、卡在谁那里？</b>
 * <p>
 * 少收件数是直接的钱：要么找亚马逊发起索赔，要么补发。
 * 因此本看板把「未结案的少收件数」单独拎出来——已结案的不需要再跟，
 * 混在一起看会让人分不清还有多少在等处理。
 */
@Data
public class ReceiptBoard implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long shopId;

    private int total;

    private int pending;
    private int investigating;
    private int resolved;

    /** 对账无差异的记录数（difference = 0），单独计数不算作差异 */
    private int matched;

    private long totalExpected;
    private long totalReceived;

    /** 实收 - 应收（全体合计，正数为多收） */
    private long totalDifference;

    /** 少收的记录数 */
    private int shortageRows;

    /** 多收的记录数 */
    private int overreceivedRows;

    /** 少收件数合计（绝对值） */
    private long shortageUnits;

    /** 多收件数合计 */
    private long overreceivedUnits;

    /** 未结案（PENDING + INVESTIGATING）的少收件数 —— 还在等处理的差额 */
    private long openShortageUnits;

    /** 差异率 = |差异| 合计 / 应收合计（0~1）；应收为 0 时 null */
    private BigDecimal discrepancyRate;

    /** 少收最多的 ASIN，按少收件数降序 */
    private List<AsinShortage> topShortageAsins;

    /** 待处理差异清单（PENDING / INVESTIGATING），按差异绝对值降序 */
    private List<DiscrepancyItem> pendingItems;

    /** 口径与数据质量提示（含「清单被截断」说明） */
    private List<String> warnings;

    /** 按 ASIN 归并的差异情况 */
    @Data
    public static class AsinShortage implements Serializable {

        private static final long serialVersionUID = 1L;

        private String asin;
        private String sku;

        /** 该 ASIN 涉及的去重货件数 */
        private int shipmentCount;

        private long expected;
        private long received;

        /** 少收件数（正数） */
        private long shortageUnits;

        /** 多收件数（正数） */
        private long overreceivedUnits;

        /** 净差异 = 实收 - 应收 */
        private long netDifference;
    }

    /** 单条差异记录（含货件号，便于直接定位） */
    @Data
    public static class DiscrepancyItem implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;
        private Long shipmentId;
        private String shipmentNo;
        private String asin;
        private String sku;
        private Integer expectedQuantity;
        private Integer receivedQuantity;
        private Integer difference;
        private String discrepancyType;
        private String status;
        private LocalDateTime createTime;
    }
}
