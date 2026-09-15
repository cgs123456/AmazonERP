package com.amz.parse;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 结算原表单行（已解析、已归一）。
 */
@Data
public class SettlementRow {

    /** 店铺 ID（由落库服务填充，解析器不感知租户）。 */
    private Long shopId;

    /** 结算批次号（settlement-id）。 */
    private String settlementId;

    /** 订单号；Adjustment（平台调整）行可能为空。 */
    private String orderId;

    /** 卖家 SKU。 */
    private String sku;

    /** 交易类型：Order / Refund / Adjustment / ServiceFee 等。 */
    private String transactionType;

    /** 金额类型：Principal / Commission / FBAPerUnitFulfillmentFee / 调整原因等。 */
    private String amountType;

    /** 有符号金额（保留平台原始方向）。 */
    private BigDecimal amount;

    /** 币种（ISO 4217）。 */
    private String currency;

    /** 结算存款日（deposit-date），用于判定回款是否已到账。 */
    private String depositDate;

    /**
     * 幂等指纹（MD5）——同一行重复导入生成相同值，落库层据此去重。
     * 组成：settlementId|orderId|sku|amountType|amount|depositDate。
     */
    private String rowKey;
}
