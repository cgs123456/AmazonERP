package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("amz_order_audit_rule")
public class OrderAuditRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String ruleName;
    private String ruleType;
    private String conditionField;
    private String conditionOp;
    private String conditionValue;
    private String action;
    private String actionParams;
    private Integer priority;
    private Boolean enabled;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
