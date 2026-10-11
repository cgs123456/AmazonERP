package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存盘点明细行。
 * <p>
 * {@code expectedQty} 是建单时的账面快照（冻结）；{@code countedQty} 为 NULL 表示
 * 该行**未盘**——apply 时跳过并计数，绝不按 0 处理（「没盘」与「盘为 0」是两件事，
 * 按 0 会把库存清空）。
 */
@Data
@TableName("amz_stocktake_item")
public class StocktakeItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stocktakeId;

    private Long shopId;

    private String sku;

    private Integer expectedQty;

    private Integer countedQty;

    private String batchNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
