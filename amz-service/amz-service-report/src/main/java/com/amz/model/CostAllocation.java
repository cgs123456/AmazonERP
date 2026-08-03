package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("amz_cost_allocation")
public class CostAllocation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String costType;
    private String sourceRef;
    private String sourceDesc;
    private BigDecimal totalAmount;
    private String currency;
    private String allocMethod;
    private String allocDetails;
    private LocalDate allocDate;
    private LocalDateTime createTime;
}
