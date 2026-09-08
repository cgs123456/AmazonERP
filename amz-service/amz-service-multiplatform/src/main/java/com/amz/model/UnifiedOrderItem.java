package com.amz.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一订单明细行（B3）。
 * <p>
 * 平台订单多商品时一对多建模：此前 parseOrders 只取 {@code items.get(0)}，
 * 多商品订单静默丢失 SKU/数量/金额。本类与
 * {@link UnifiedOrder#addItem(String, String, Integer)} 配合，
 * 明细全量保留；订单头 sku/productName/quantity 仍回填首行，保持
 * 列表展示与历史数据的兼容。
 */
@Data
public class UnifiedOrderItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品 SKU（各平台字段名不同，解析时归一） */
    private String sku;

    /** 商品名称 */
    private String productName;

    /** 购买数量（缺失为 null，不臆测） */
    private Integer quantity;
}
