package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("amz_order_split_log")
public class OrderSplitLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String originalOrderId;
    private String splitOrderId;
    private String splitReason;
    private String splitItems;
    private String operator;
    private LocalDateTime splitTime;
}
