package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 入库短收行（签收数 &lt; 发货数）——财务登记 INBOUND_SHORTAGE 费用差异的事实来源。
 * <p>
 * 只给事实：应发/实发/短收件数，以及我们自己的成本口径（单位成本、行总成本）。
 * <b>刻意不直接生成差异金额</b>：货件成本分摊字段没有记币种，而亚马逊赔付按站点币种结算，
 * 把 CNY 口径的分摊值当成 USD 索赔额会算错钱。登记时由人工确认单价与币种
 * （财务侧「登记入库短收」表单已必填 unitAmount/currency），这里只负责让人看见漏了什么。
 */
@Data
public class ReceiptShortage {

    /** 货件明细 ID（本页游标即按它倒序推进） */
    private Long itemId;
    private Long shipmentId;
    private String shipmentNo;
    /** 亚马逊侧货件号：财务差异表的 shipmentId 用的是它，不是内部 ID */
    private String fbaShipmentId;
    private String sku;
    private String asin;
    private Integer expectedQty;
    private Integer receivedQty;
    private Integer shortUnits;
    /** 本系统的单位成本口径，币种未记录 */
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private String shipmentStatus;
}
