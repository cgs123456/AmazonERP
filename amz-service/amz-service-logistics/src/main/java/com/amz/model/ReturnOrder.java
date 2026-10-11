package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货单（买家退货入仓）。
 * <p>
 * 状态流转（服务层白名单强制）：
 * <pre>
 * PENDING(待收货) → RECEIVING(收货中) → RECEIVED(已收货) → CLOSED(已结案)
 * PENDING → CANCELLED(已取消)
 * </pre>
 * 处置（disposition）在收货时确定：GOOD / DEFECTIVE / SCRAP。
 * <p>
 * <b>与退款的关系</b>：退款金额在财务域（结算报表），本单只记实物退回。
 * 亚马逊有「退款不退货」场景，因此 refund_amount 允许为空，两者不是一对一。
 */
@Data
@TableName("amz_return_order")
public class ReturnOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotNull(message = "shopId 不能为空")
    private Long shopId;

    /** 退货单号（RET + 时间戳 + 随机数），唯一 */
    private String returnNo;

    private String amazonOrderId;

    @NotBlank(message = "SKU 不能为空")
    private String sku;

    private String asin;

    private Long warehouseId;

    @NotNull(message = "退货数量不能为空")
    @Min(value = 1, message = "退货数量必须大于 0")
    private Integer quantity;

    /** 处置：GOOD / DEFECTIVE / SCRAP（收货时确定） */
    private String disposition;

    /** 来源：AMAZON_FBA / AMAZON_FBM / MANUAL */
    private String source;

    private String returnReason;

    private BigDecimal refundAmount;

    private String currency;

    /** 状态：PENDING / RECEIVING / RECEIVED / CLOSED / CANCELLED */
    private String status;

    private String settlementRowKey;

    /**
     * 外部幂等键（店铺|来源|业务指纹）。手工建单为 null；
     * Amazon 退货报表导入时必填，唯一索引据此拦截重复导入。
     */
    private String externalKey;

    private Long operatorId;

    private LocalDateTime receivedAt;

    private LocalDateTime closedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
