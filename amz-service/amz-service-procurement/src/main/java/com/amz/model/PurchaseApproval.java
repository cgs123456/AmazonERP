package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审批留痕实体（amz_purchase_approval）。
 * <p>
 * 与 {@link PurchasePlan#getStatus()} 的分工：status 只存「当前状态」与被覆盖一次的
 * approvedBy/approvedTime/remark；这张表存**每一次** APPROVE/REJECT 动作、操作人与意见。
 * 此前这张表全仓零引用，等于审批过程不留痕（改一次状态就丢掉上一次的记录）。
 * <p>
 * 注意：operator 仍来自调用方自报的请求参数，可信身份没有进表结构；
 * 要记录 JWT 里的用户 id 属于 schema 变更，另立项处理。
 */
@Data
@TableName("amz_purchase_approval")
public class PurchaseApproval implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    /** 关联单据类型：PLAN / ORDER */
    private String refType;
    /** 关联单据 ID */
    private Long refId;
    /** APPROVE / REJECT，取自 DDL 注释 */
    private String action;
    private String operator;
    private String comment;
    private LocalDateTime createTime;
}
