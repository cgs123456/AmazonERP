package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 广告自动规则实体。
 */
@Data
@TableName("amz_ad_auto_rule")
public class AdAutoRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String ruleName;
    private String ruleType;
    private String scope;
    private String scopeValue;
    private String conditionField;
    private String conditionOp;
    private BigDecimal conditionValue;
    private BigDecimal conditionValue2;
    private String action;
    private BigDecimal actionValue;
    private Integer timeWindow;
    private Integer priority;
    private Integer enabled;
    private LocalDateTime lastExecuted;
}
