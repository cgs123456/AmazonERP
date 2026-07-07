package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 会计凭证实体（业财一体化核心）。
 * <p>
 * 复式记账：每条凭证含借方(debit)+贷方(credit)，金额必相等。
 * 自动生成场景：
 * <ul>
 *   <li>订单签收：借 应收账款 / 贷 主营业务收入</li>
 *   <li>采购入库：借 库存商品 / 贷 应付账款</li>
 *   <li>平台扣费：借 销售费用 / 贷 银行存款</li>
 *   <li>退款退货：借 销售退回 / 贷 应收账款</li>
 * </ul>
 * 多币种：原币金额 + 汇率 → 本位币金额（CNY）
 */
@Data
@TableName("amz_accounting_voucher")
public class AccountingVoucher implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 凭证编号 */
    private String voucherNo;

    /** 所属店铺 ID */
    private Long shopId;

    /** 业务日期（ISO 格式） */
    private String bizDate;

    /** 摘要 */
    private String summary;

    /** 借方科目代码 */
    private String debitAccount;

    /** 贷方科目代码 */
    private String creditAccount;

    /** 原币金额 */
    private BigDecimal originalAmount;

    /** 原币币种：USD/EUR/GBP/JPY */
    private String currency;

    /** 汇率（原币→CNY） */
    private BigDecimal exchangeRate;

    /** 本位币金额（CNY） */
    private BigDecimal cnyAmount;

    /** 业务来源：ORDER/PROCUREMENT/PLATFORM_FEE/REFUND */
    private String sourceType;

    /** 关联业务单号 */
    private String sourceNo;

    /** 同步金蝶状态：PENDING / SYNCED / FAILED */
    private String kingdeeSyncStatus;
}
