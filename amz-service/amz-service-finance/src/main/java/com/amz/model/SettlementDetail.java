package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算原表明细（订单级资金流水）。
 * <p>
 * 来源：SP-API 结算报表（GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE）经 Tab 分隔的扁平文件，
 * 由 {@code SettlementParser} 解析后落库。一行 = 一笔资金影响
 * （一个订单可能有 Principal / Commission / FBAPerUnitFulfillmentFee 等多行）。
 * <p>
 * <b>幂等</b>：{@code row_key}（业务指纹 MD5）建唯一索引，重复导入的同一行被跳过而非重复入账 ——
 * 结算报表按批次下发，跨窗口重叠拉取是常态。
 */
@Data
@TableName("amz_settlement_detail")
public class SettlementDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据来源：SP-API 报表拉取。 */
    public static final String SOURCE_REPORT = "REPORT";
    /** 数据来源：人工导入。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺 ID。 */
    private Long shopId;

    /** 结算批次号（settlement-id）。 */
    private String settlementId;

    /** 订单号；平台调整行可能为空。 */
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

    /** 结算存款日（deposit-date，原样保存平台字符串）。 */
    private String depositDate;

    /** 幂等指纹（MD5），唯一索引。 */
    private String rowKey;

    /** 来源：REPORT / MANUAL。 */
    private String source;

    private LocalDateTime createTime;
}
