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
 * 索赔单（T07）：把「发现平台少给钱」推进到「把钱要回来」。
 * <p>
 * <b>状态机（单向推进，跳过前置一律拒绝）</b>：
 * <pre>
 *   CANDIDATE ──提交──▶ SUBMITTED ──受理──▶ ACCEPTED ──赔付──▶ REIMBURSED（终态）
 *                          │                    │
 *                          └──── 驳回 ──────────┘──▶ REJECTED（终态）
 * </pre>
 * 为什么必须有状态机：没有它，「已赔付」可以从前置任意状态一步跳到，
 * 一笔从未提交的候选就能被标成已追回，统计出来的追回金额全是假的。
 * <p>
 * 幂等：重复执行同一动作（如重复提交）返回当前状态而不报错 ——
 * 前端重试、用户双击都是常态，不该因此产生错误提示。
 */
@Data
@TableName("amz_reimbursement_claim")
public class ReimbursementClaim implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 候选（已生成索赔单，尚未提交）。 */
    public static final String STATUS_CANDIDATE = "CANDIDATE";
    /** 已提交平台。 */
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    /** 平台已受理。 */
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    /** 已赔付（终态）。 */
    public static final String STATUS_REIMBURSED = "REIMBURSED";
    /** 已驳回（终态）。 */
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 索赔单号（CLM + 毫秒时间戳 + 随机数），唯一。 */
    private String claimNo;

    /** 来源差异候选 ID（amz_fee_discrepancy.id）。 */
    private Long discrepancyId;

    /** 差异类型（继承自候选，便于按类型统计成功率）。 */
    private String discrepancyType;

    private String sku;
    private String shipmentId;

    /** 索赔理由 / 举证（继承自候选的 evidence，可人工补充）。 */
    private String claimReason;

    /** 申请索赔金额（取候选差额绝对值）。 */
    private BigDecimal claimAmount;

    /** 实际赔付金额（赔付时填写，可能少于申请金额）。 */
    private BigDecimal reimbursedAmount;

    private String currency;

    /** CANDIDATE / SUBMITTED / ACCEPTED / REIMBURSED / REJECTED。 */
    private String status;

    private LocalDateTime submittedAt;
    private LocalDateTime acceptedAt;
    /** 结案时间（赔付或驳回）。 */
    private LocalDateTime settledAt;

    /** 驳回原因。 */
    private String rejectReason;

    /** 追回入账凭证 ID（T08）。 */
    private Long voucherId;

    /** 操作人（用户 ID）。 */
    private Long operatorId;

    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
