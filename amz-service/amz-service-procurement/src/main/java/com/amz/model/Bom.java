package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物料清单（BOM，组合装）。
 * <p>
 * composite_sku 是亚马逊在售组合 SKU；child_sku 是本地仓备货粒度的子品。
 * 履约出库前按 BOM 展开成子品需求再扣减（递归展开，环检测强制）。
 */
@Data
@TableName("amz_bom")
public class Bom implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String compositeSku;

    private String childSku;

    private Integer childQty;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
