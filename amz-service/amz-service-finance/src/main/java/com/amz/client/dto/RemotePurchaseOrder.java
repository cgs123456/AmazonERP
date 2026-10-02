package com.amz.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购域「成本可确认」采购单的财务视角投影（Feign 解码用）。
 * <p>
 * 只声明财务出凭证真正需要的列：单据号决定幂等，数量×单价决定成本，状态是筛选前提。
 * 刻意不复用采购域实体——实体加字段不该顺带改变财务口径。
 * <p>
 * 没有 currency 字段：1688 的报价与支付都是人民币，采购单实体本身也没有币种列。
 * 因此财务侧按 CNY 记账是「与数据源同假设」，不是猜测；将来若接入外币采购，
 * 必须先让采购单带币种，不能继续在这里硬编。
 */
@Data
public class RemotePurchaseOrder {

    private String orderNo;
    private Long shopId;
    private String sku;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private String status;
}
