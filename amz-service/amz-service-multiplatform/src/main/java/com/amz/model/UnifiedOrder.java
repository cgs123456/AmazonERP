package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 多平台统一订单实体。
 * <p>
 * 将 Temu / TikTok Shop / Shein 三个平台的订单拉取后归一化存储，
 * platform 字段标识来源平台，原始订单号保留在 platformOrderNo 字段中。
 * <p>
 * 状态映射：
 * <pre>
 * UNPAID(未支付) → PAID(已支付) → SHIPPED(已发货) → DELIVERED(已签收) → COMPLETED(已完成)
 * 异常：CANCELED(已取消) / REFUNDED(已退款)
 * </pre>
 */
@Data
@TableName("amz_unified_order")
public class UnifiedOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统一订单号（系统生成，前缀 UO） */
    private String unifiedOrderNo;

    /** 来源平台：TEMU / TIKTOK / SHEIN */
    private String platform;

    /** 平台原始订单号 */
    private String platformOrderNo;

    /** 所属店铺 ID */
    private Long shopId;

    /** 买家昵称 */
    private String buyerNickname;

    /** 收件国家（ISO 2 位代码，如 US/UK/DE） */
    private String shipCountry;

    /** 商品 SKU（首行回填，兼容历史单明细展示） */
    private String sku;

    /** 商品名称（首行回填） */
    private String productName;

    /** 购买数量（首行回填） */
    private Integer quantity;

    /**
     * 订单明细行（一对多，持久化于 items_json 列，读写经 {@link UnifiedOrderItems}）。
     * <p>
     * 多商品订单此前只保留首行，其余静默丢失；现全量保留，
     * 头字段仍回填首行以兼容列表展示与历史数据。
     */
    @TableField(exist = false)
    private List<UnifiedOrderItem> items;

    /** 明细 JSON（amz_unified_order.items_json，Flyway V2 新增列） */
    private String itemsJson;

    /**
     * 追加明细行；首行同时回填头字段（仅当头字段仍为空时，避免覆盖调用方预设值）。
     */
    public void addItem(String sku, String productName, Integer quantity) {
        if (items == null) {
            items = new ArrayList<>();
        }
        UnifiedOrderItem item = new UnifiedOrderItem();
        item.setSku(sku);
        item.setProductName(productName);
        item.setQuantity(quantity);
        items.add(item);
        if (items.size() == 1) {
            if (this.sku == null) {
                this.sku = sku;
            }
            if (this.productName == null) {
                this.productName = productName;
            }
            if (this.quantity == null) {
                this.quantity = quantity;
            }
        }
    }

    /** 订单金额（原币种） */
    private BigDecimal originalAmount;

    /** 币种（USD/EUR/GBP 等） */
    private String currency;

    /** 折算人民币金额 */
    private BigDecimal cnyAmount;

    /** 状态：UNPAID/PAID/SHIPPED/DELIVERED/COMPLETED/CANCELED/REFUNDED */
    private String status;

    /** 平台物流单号（发货后回填） */
    private String trackingNo;

    /** 下单时间（平台返回） */
    private String orderCreateTime;
}
