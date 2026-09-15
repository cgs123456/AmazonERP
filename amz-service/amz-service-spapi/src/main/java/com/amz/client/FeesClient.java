package com.amz.client;

import com.amz.client.dto.FeeEstimate;

import java.math.BigDecimal;

/**
 * SP-API Fees API（v0）客户端：FBA 费用预估。
 * <p>
 * 与实际扣费（来自结算原表）比对，是识别「配送费多收 / 尺寸重测跳档」等
 * 索赔候选的数据基础。
 * <p>
 * 支持两种标识方式（对应 SP-API 的 {@code IdType}）：
 * <ul>
 *   <li>{@code ASIN} —— 已知 ASIN 时精度最高</li>
 *   <li>{@code SKU} —— 结算原表只带 SKU、不带 ASIN，费用比对场景主要走这条</li>
 * </ul>
 */
public interface FeesClient {

    String ID_TYPE_ASIN = "ASIN";
    String ID_TYPE_SKU = "SKU";

    /**
     * 预估商品费用（指定标识类型）。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId 目标 Marketplace ID
     * @param idType        标识类型：{@link #ID_TYPE_ASIN} 或 {@link #ID_TYPE_SKU}
     * @param idValue       标识值（ASIN 或 SKU）
     * @param sku           卖家 SKU（可选，仅用于结果标注）
     * @param price         用于估价的售价
     * @param currency      币种（ISO 4217，如 USD）
     * @return 费用预估结果
     */
    FeeEstimate estimateFbaFees(Long shopId, String marketplaceId, String idType, String idValue,
                                String sku, BigDecimal price, String currency);

    /**
     * 按 ASIN 预估（便捷重载）。
     */
    default FeeEstimate estimateFbaFees(Long shopId, String marketplaceId, String asin, String sku,
                                        BigDecimal price, String currency) {
        return estimateFbaFees(shopId, marketplaceId, ID_TYPE_ASIN, asin, sku, price, currency);
    }
}
