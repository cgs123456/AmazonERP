package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入库单实体。
 * <p>
 * 状态流转：
 * <pre>
 * PENDING(待入库) → IN_TRANSIT(运输中) → RECEIVED(已收货)
 *   → PARTIAL(部分收货) / CANCELLED(已取消)
 * </pre>
 * 来源：FBA_TRANSFER(FBA 调拨) / 1688_PURCHASE(1688 采购) / OTHER(其他)
 */
@Data
@TableName("amz_inbound_order")
public class InboundOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private Long warehouseId;

    private String inboundNo;

    /** 来源：FBA_TRANSFER / 1688_PURCHASE / OTHER */
    private String source;

    /** 关联单号（采购单号 / FBA 货件号） */
    private String referenceNo;

    /** 状态：PENDING / IN_TRANSIT / RECEIVED / PARTIAL / CANCELLED */
    private String status;

    private Integer totalItems;

    private Integer receivedItems;

    private LocalDate expectedArrival;

    private LocalDateTime actualArrival;

    private String remark;
}
