package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 入库短收登记请求（T06 的第四类差异入口）。
 * <p>
 * <b>为什么是人工/接口登记而不是自动识别</b>：入库短收的索赔金额 =
 * 少收数量 × 单位成本，而「单位成本」在采购域（FIFO 批次成本），
 * 与物流域的签收数量分属两个服务。跨域成本对齐尚未建立之前，
 * 自动推算出来的索赔金额是编的 —— 这里接受显式的单位成本，宁可让调用方把数字给准。
 */
@Data
public class InboundShortageRequest {

    /** 卖家 SKU。 */
    private String sku;

    /** 关联货件编号。 */
    private String shipmentId;

    /** 少收数量（正数）。 */
    private Integer shortageUnits;

    /** 单位成本（用于折算索赔金额）。 */
    private BigDecimal unitAmount;

    /** 币种（ISO 4217）。 */
    private String currency;

    /** 备注 / 举证说明（如物流签收差异单号）。 */
    private String note;
}
