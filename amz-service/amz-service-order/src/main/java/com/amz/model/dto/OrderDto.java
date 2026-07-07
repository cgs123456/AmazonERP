package com.amz.model.dto;

import com.amz.model.pojo.CustomAttribute;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderDto implements Serializable {

    /**
     * 产品id
     */
    private Integer productId;

    /**
     * 产品价格
     */
    private BigDecimal price;

    /**
     * 选择的属性
     */
    private List<CustomAttribute> selectAttributes;

    /**
     * 用户ID
     */
    private Integer userId;

    /**
     * 消息唯一标识（用于幂等性控制）
     */
    private String messageId;
}
