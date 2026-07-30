package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 出库单实体。
 * <p>
 * 状态流转：
 * <pre>
 * PENDING(待出库) → PICKING(拣货中) → PACKED(已打包) → SHIPPED(已发货)
 *   → CANCELLED(已取消)
 * </pre>
 * 出库类型：ORDER(订单出库) / TRANSFER(调拨) / RETURN(退货) / SCRAP(报废)
 */
@Data
@TableName("amz_outbound_order")
public class OutboundOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private Long warehouseId;

    private String outboundNo;

    /** 出库类型：ORDER / TRANSFER / RETURN / SCRAP */
    private String orderType;

    /** 关联单号 */
    private String referenceNo;

    /** 状态：PENDING / PICKING / PACKED / SHIPPED / CANCELLED */
    private String status;

    private String carrier;

    private String trackingNo;

    private Integer totalItems;

    private Integer shippedItems;

    private LocalDateTime shipDate;

    private String remark;
}
