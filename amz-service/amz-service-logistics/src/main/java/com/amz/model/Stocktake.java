package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存盘点单（账实核对）。
 * <p>
 * 状态机（服务层白名单强制）：
 * <pre>
 * DRAFT(盘点中) → COUNTED(已录入实盘) → APPLIED(已按实盘修正)
 * DRAFT / COUNTED → CANCELLED
 * </pre>
 * 修正只发生在 APPLIED 一步：以「apply 时的真实账面 → 实盘值」写
 * {@code STOCKTAKE_ADJUST} 流水；建单时的 {@code expectedQty} 只是快照，
 * 用于差异报告，不直接参与修正（快照到 apply 之间账面可能已被出入库改变）。
 */
@Data
@TableName("amz_stocktake")
public class Stocktake implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_COUNTED = "COUNTED";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String stocktakeNo;

    private Long warehouseId;

    private String status;

    private String remark;

    private Long operatorId;

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
