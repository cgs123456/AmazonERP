package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 质检单实体。
 * <p>
 * 质检流程：到货 → 抽检 → 记录合格数/不合格数/缺陷描述 → 判定 PASS/FAIL/CONDITIONAL。
 * <ul>
 *   <li>PASS：合格数 ≥ 期望数 × 合格率阈值（默认 95%）→ 入库</li>
 *   <li>FAIL：合格率 &lt; 阈值 → 退货/返工</li>
 *   <li>CONDITIONAL：合格率处于边界（如 90%-95%）→ 让步接收，需人工审批</li>
 * </ul>
 */
@Data
@TableName("amz_quality_check")
public class QualityCheck implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联采购单 ID */
    private Long purchaseOrderId;

    /** 抽检总数 */
    private Integer sampleCount;

    /** 合格数 */
    private Integer passedCount;

    /** 不合格数 */
    private Integer failedCount;

    /** 合格率（自动计算） */
    private BigDecimal passRate;

    /** 缺陷类型描述 */
    private String defectDescription;

    /** 质检结果：PASS / FAIL / CONDITIONAL */
    private String result;

    /** 质检员 */
    private String inspector;
}
