package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FBA 库存主表实体。
 * <p>
 * 对应 amz_spapi.amz_fba_inventory 表，存储 Amazon FBA 库存快照与健康度分析结果。
 * 唯一键：shop_id + marketplace_id + sku。
 */
@Data
@TableName("amz_fba_inventory")
public class FbaInventory {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 店铺 ID。
     */
    @TableField("shop_id")
    private Long shopId;

    /**
     * Amazon Marketplace ID（如 ATVPDKIKX0DER）。
     */
    @TableField("marketplace_id")
    private String marketplaceId;

    /**
     * 卖家 SKU。
     */
    @TableField("sku")
    private String sku;

    /**
     * ASIN。
     */
    @TableField("asin")
    private String asin;

    /**
     * Fulfillment Network SKU。
     */
    @TableField("fn_sku")
    private String fnSku;

    /**
     * 商品名。
     */
    @TableField("product_name")
    private String productName;

    /**
     * 可售库存（fulfillable）。
     */
    @TableField("available_quantity")
    private Integer availableQuantity;

    /**
     * 不可售库存（unfulfillable）。
     */
    @TableField("unfulfillable_quantity")
    private Integer unfulfillableQuantity;

    /**
     * 在途入库（inboundWorking）。
     */
    @TableField("inbound_working")
    private Integer inboundWorking;

    /**
     * 已发货入库（inboundShipped）。
     */
    @TableField("inbound_shipped")
    private Integer inboundShipped;

    /**
     * Amazon 最后更新时间。
     */
    @TableField("last_updated_time")
    private LocalDateTime lastUpdatedTime;

    /**
     * 本地同步时间。
     */
    @TableField("sync_time")
    private LocalDateTime syncTime;

    /**
     * 库存可供天数（Days Of Supply）。
     */
    @TableField("days_of_supply")
    private BigDecimal daysOfSupply;

    /**
     * 7 天日均销量。
     */
    @TableField("avg_7_days")
    private BigDecimal avg7Days;

    /**
     * 30 天日均销量。
     */
    @TableField("avg_30_days")
    private BigDecimal avg30Days;

    /**
     * 健康度状态：URGENT/AT_RISK/HEALTHY/OVERSTOCK/STOCKOUT。
     */
    @TableField("health_status")
    private String healthStatus;
}
