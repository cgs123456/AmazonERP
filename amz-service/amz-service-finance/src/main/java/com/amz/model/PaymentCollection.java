package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单级回款台账。
 * <p>
 * 由 {@link SettlementDetail} 按订单号聚合而来（见 PaymentCollectionService#rebuild），
 * 回答卖家最常问的那个问题：<b>这笔订单的钱到了没、到手多少</b>。
 * <p>
 * 金额口径（全部为有符号聚合，方向与平台一致）：
 * <ul>
 *   <li>{@code receivable} 应收 = Σ INCOME（Order + Principal）</li>
 *   <li>{@code feeDeducted} 平台费用 = Σ |FEE|（佣金、FBA 配送费等，存正数量级便于阅读）</li>
 *   <li>{@code refunded} 退款 = Σ |REFUND|（正数量级）</li>
 *   <li>{@code reimbursed} 平台赔付净额 = Σ ADJUSTMENT（保留符号，赔付为正）</li>
 *   <li>{@code netReceived} 实收 = receivable - feeDeducted - refunded + reimbursed</li>
 *   <li>{@code shortfall} 短款 = 预估净回 - netReceived，<b>由费用比对（T06）填充</b>；
 *       未做费用比对时为 null 而非 0 —— 0 会被读成「没有短款」这个结论</li>
 * </ul>
 * 回款状态（{@link #status}）：
 * PENDING 未回（无结算行） / IN_TRANSIT 在途（已结算未到账） /
 * SETTLED 已回（存款日已到） / REFUNDED 已退（退款冲销） / SHORTFALL 有差额（优先于已回）。
 */
@Data
@TableName("amz_payment_collection")
public class PaymentCollection implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未回：无任何结算行。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 在途：有结算行但存款日未到（或缺失）。 */
    public static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    /** 已回：存款日已到。 */
    public static final String STATUS_SETTLED = "SETTLED";
    /** 已退：退款额 ≥ 应收，订单资金已冲销。 */
    public static final String STATUS_REFUNDED = "REFUNDED";
    /** 有差额：费用比对发现短款（优先于「已回」呈现，因为它是需要动作的状态）。 */
    public static final String STATUS_SHORTFALL = "SHORTFALL";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 订单号。 */
    private String orderId;

    private String currency;

    /** 应收（Σ INCOME）。 */
    private BigDecimal receivable;

    /** 平台费用（正数量级）。 */
    private BigDecimal feeDeducted;

    /** 退款（正数量级）。 */
    private BigDecimal refunded;

    /** 平台赔付净额（保留符号）。 */
    private BigDecimal reimbursed;

    /** 实收 = 应收 - 费用 - 退款 + 赔付。 */
    private BigDecimal netReceived;

    /** 短款（预估净回 - 实收）；由费用比对填充，未比对时 null。 */
    private BigDecimal shortfall;

    /** 结算存款日（原样保留平台字符串）。 */
    private String depositDate;

    /** 回款状态。 */
    private String status;

    /** 本次重算时间。 */
    private LocalDateTime lastCalculatedAt;

    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
