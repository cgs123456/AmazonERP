package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水账（库存变更留痕，只增不改）。
 * <p>
 * 存在动因：全仓此前没有任何库存移动账，库存只有「快照 + 单头」，
 * 变更不可追溯、不可重放、不可对账。退货回补尤其需要它——
 * 货退回入仓后如果对不上账，没有流水就只能靠猜。
 * <p>
 * <b>只增不改</b>：流水是事实记录，任何 update 都是错误用法。
 * <p>
 * qtyBefore / qtyAfter 必填：只记 delta 无法发现「并发下 before 已经变了」这类问题。
 */
@Data
@TableName("amz_inventory_movement")
public class InventoryMovement implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务动作：退货回补。 */
    public static final String TYPE_RETURN_INBOUND = "RETURN_INBOUND";
    /** 业务动作：入库验收。 */
    public static final String TYPE_INBOUND_RECEIVE = "INBOUND_RECEIVE";
    /** 业务动作：出库发运。 */
    public static final String TYPE_OUTBOUND_SHIP = "OUTBOUND_SHIP";
    /** 业务动作：FIFO 批次出库。 */
    public static final String TYPE_FIFO_OUTBOUND = "FIFO_OUTBOUND";
    /** 业务动作：FIFO 批次回补（退货）。 */
    public static final String TYPE_FIFO_RETURN = "FIFO_RETURN";
    /** 业务动作：调拨发出。 */
    public static final String TYPE_TRANSFER_OUT = "TRANSFER_OUT";
    /** 业务动作：调拨收货。 */
    public static final String TYPE_TRANSFER_IN = "TRANSFER_IN";
    /** 业务动作：盘点差异调整（可正可负，以实盘为准修正账面）。 */
    public static final String TYPE_STOCKTAKE_ADJUST = "STOCKTAKE_ADJUST";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private Long warehouseId;

    private String sku;

    private String batchNo;

    private Integer qtyBefore;

    private Integer qtyAfter;

    /** 变更量：正数入库、负数出库 */
    private Integer qtyDelta;

    private String movementType;

    private String sourceType;

    private String sourceNo;

    private BigDecimal unitCost;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;
}
