package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FBA 货件签收差异实体（B6：管「货」的那一半）。
 *
 * <p><b>与 finance 域 `inbound-shortage`（入库短收）的分工</b>——这不是重复建模，
 * 两个领域的不同职责：
 * <ul>
 *   <li>本实体在 logistics 域，管<b>货</b>：货件签收时箱内实收 vs 应得数量的差异，
 *       是「东西少了多少」的物理事实，也是后续索赔的<b>依据</b>；</li>
 *   <li>finance 的入库短收管<b>钱</b>：把货的差异折算成可申报的索赔金额，
 *       走费用差异（FeeDiscrepancy）流程。</li>
 * </ul>
 * 所以这里只登记数量差异、不碰金额；合并两个域会让物流侧改动牵连财务侧金额，
 * 违背领域边界。页面同样披露该分工，防止使用者以为两处登记是重复劳动。
 */
@Data
@TableName("amz_fba_receipt_discrepancy")
public class FbaReceiptDiscrepancy implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private Long shipmentId;
    private String asin;
    private String sku;
    private Integer expectedQuantity;
    private Integer receivedQuantity;
    private Integer difference;
    private String discrepancyType;
    private String status;
    private String resolution;

    /** 登记时间 */
    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 最近处理时间。由 MySQL {@code ON UPDATE CURRENT_TIMESTAMP} 维护，业务代码不写。 */
    @TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
