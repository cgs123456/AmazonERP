package com.amz.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 库存调拨看板。
 * <p>
 * 回答的问题：<b>有多少调拨单卡在审批？有多少发出去了迟迟不到货？</b>
 * <p>
 * 调拨是「仓库之间的货」，卡住的直接后果是某个仓缺货、另一个仓压库存，
 * 而状态字段本身只显示 IN_TRANSIT，看不出已经卡了多久。
 * 因此本看板除状态分布外，还按「在途天数」挑出风险单据。
 */
@Data
public class TransferBoard implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long shopId;

    private int total;

    /** 状态 → 单据数（含全部合法状态的 0 值补齐） */
    private Map<String, Integer> byStatus;

    /** DRAFT + PENDING_APPROVAL：等人工审批 */
    private int pendingApproval;

    /** APPROVED：已批待发 */
    private int approvedNotShipped;

    private int inTransit;

    private int received;

    private int cancelled;

    /** 全部调拨单的运费合计 */
    private BigDecimal totalShippingCost;

    /** 在途超期未收货的单据数（超过 {@link #staleThresholdDays} 天） */
    private int staleInTransit;

    /** 在途超期判定阈值（天） */
    private int staleThresholdDays;

    /** 需要人工跟进的调拨单，按在途天数降序 */
    private List<TransferRisk> risks;

    /** 口径与数据质量提示（含「清单被截断」说明） */
    private List<String> warnings;

    /** 需要跟进的调拨单 */
    @Data
    public static class TransferRisk implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;
        private String transferNo;
        private Long fromWarehouseId;
        private Long toWarehouseId;
        private String asin;
        private String sku;
        private Integer quantity;
        private String carrier;
        private String trackingNo;
        private BigDecimal shippingCost;
        private String status;

        /** 建单至今的天数；时间缺失时为 null（不是 0） */
        private Integer daysSinceCreated;

        /** 最近一次状态变更时间 */
        private LocalDateTime updateTime;

        /** 风险类型：STALE_IN_TRANSIT / PENDING_APPROVAL_TOO_LONG / MISSING_TRACKING_NO */
        private String type;

        /** HIGH / MEDIUM / LOW，前端按此排序与配色 */
        private String severity;

        private String message;

        private String actionHint;
    }
}
