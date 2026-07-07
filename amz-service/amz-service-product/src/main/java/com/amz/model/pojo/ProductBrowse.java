package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("rb_product_browse")
public class ProductBrowse implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 产品id
     */
    @TableField("product_id")
    private Integer productId;

    /**
     * 用户id
     */
    @TableField("user_id")
    private Integer userId;
}
