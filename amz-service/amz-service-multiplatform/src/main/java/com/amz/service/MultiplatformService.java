package com.amz.service;

import com.amz.model.UnifiedOrder;

import java.util.List;

/**
 * 多平台订单聚合服务接口。
 * <p>
 * 提供 Temu / TikTok Shop / Shein 订单的统一拉取、归一化存储、按平台/状态筛选等能力。
 */
public interface MultiplatformService {

    /**
     * 从所有平台拉取最新订单并落库。
     *
     * @param shopId 店铺 ID
     * @return 新增的统一订单数
     */
    int syncAllPlatforms(Long shopId);

    /**
     * 从指定平台拉取最新订单。
     *
     * @param shopId   店铺 ID
     * @param platform 平台标识：TEMU / TIKTOK / SHEIN
     * @return 新增的统一订单数
     */
    int syncByPlatform(Long shopId, String platform);

    /**
     * 查询店铺所有平台的订单列表。
     */
    List<UnifiedOrder> listOrders(Long shopId);

    /**
     * 按平台筛选订单。
     */
    List<UnifiedOrder> listByPlatform(Long shopId, String platform);

    /**
     * 向平台回传发货信息。
     *
     * @param orderId    统一订单 ID
     * @param trackingNo 物流单号
     * @return 是否回传成功
     */
    boolean markShipped(Long orderId, String trackingNo);
}
