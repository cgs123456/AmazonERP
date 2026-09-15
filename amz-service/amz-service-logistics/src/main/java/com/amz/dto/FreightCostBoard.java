package com.amz.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 头程成本看板。
 * <p>
 * 回答的问题：<b>头程花了多少钱、摊到每件多少、哪些货件的成本还没核算？</b>
 * <p>
 * 分摊覆盖率是这里的核心指标：没登记分摊明细的货件，头程成本不会进入利润计算，
 * 表现为「这个货件明明花了运费，但财务上没体现」。这类缺口不主动暴露就永远没人补。
 */
@Data
public class FreightCostBoard implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long shopId;

    /** 分摊明细行数 */
    private int totalAllocationRows;

    /** 分摊数量合计（件） */
    private long totalQuantity;

    /** 已登记分摊明细的货件数 */
    private int coveredShipments;

    /** 没有任何分摊明细的货件数 —— 头程成本未核算 */
    private int uncoveredShipments;

    /** 分摊覆盖率（0~1）；无货件时为 null 而不是 0 */
    private BigDecimal coverageRate;

    private BigDecimal totalFreight;
    private BigDecimal totalDuty;
    private BigDecimal totalInsurance;
    private BigDecimal totalOther;

    /** 头程成本合计 */
    private BigDecimal totalCost;

    /** 加权单件头程成本 = 总成本 / 总数量；总数量为 0 时为 null */
    private BigDecimal avgUnitCost;

    /** 分摊方法 → 明细行数，用于发现「口径不统一」 */
    private Map<String, Integer> methodMix;

    /** 未核算头程成本的货件（最多返回前若干条，用于去补数据） */
    private List<UncoveredShipment> uncoveredList;

    /** 单件成本最高的明细，用于识别成本异常品 */
    private List<CostItem> topUnitCostItems;

    /** 口径与数据质量提示（含「清单被截断」说明） */
    private List<String> warnings;

    /** 未核算成本明细的货件 */
    @Data
    public static class UncoveredShipment implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long shipmentId;
        private String shipmentNo;
        private String carrier;
        private String status;
        private String eta;
        private BigDecimal freightCost;

        /** 该货件在货件主单上已登记的运费；有值但没分摊，说明是「有费用未摊」而非「无费用」 */
        private boolean hasDeclaredFreight;
    }

    /** 单件头程成本明细 */
    @Data
    public static class CostItem implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long shipmentId;
        private String shipmentNo;
        private String asin;
        private String sku;
        private Integer quantity;
        private BigDecimal totalCost;
        private BigDecimal unitCost;
        private String allocationMethod;
    }
}
