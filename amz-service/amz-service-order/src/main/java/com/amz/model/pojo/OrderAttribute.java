package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("amz_order_attribute")
public class OrderAttribute {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 订单id
     */
    @TableField("order_id")
    private Long orderId;

    /**
     * 属性标签
     */
    /** 列名是 name：字段沿用 label 是为了不改 JSON 契约，映射必须显式对齐。 */
    @TableField("name")
    private String label;

    /**
     * 属性值
     */
    private String value;
}
