package com.amz.service;

import com.amz.dto.SkuProfitReport;

/**
 * 单品真实利润服务（T09）。
 * <p>
 * 收入与费用来自结算原表（平台实际扣费，非估算），赔付追回来自索赔单，
 * 成本来自采购批次。三者都拿到才算「真实利润」，缺任何一项都要在报告里显式标注。
 */
public interface SkuProfitService {

    /**
     * 计算指定店铺的单品利润。
     *
     * @param shopId        店铺 ID
     * @param depositAfter  结算存款日起始（ISO 字符串，可为 null）
     * @param depositBefore 结算存款日截止（ISO 字符串，可为 null）
     * @param sku           仅计算指定 SKU（可为 null 表示全部）
     * @return 单品利润报告
     */
    SkuProfitReport compute(Long shopId, String depositAfter, String depositBefore, String sku);
}
