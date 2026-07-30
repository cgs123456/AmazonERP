package com.amz.client;

import com.amz.model.UnifiedOrder;

import java.util.List;

/**
 * Temu 开放平台客户端接口。
 * <p>
 * 生产环境对接路径（参考 Temu Seller Center Open API）：
 * <ol>
 *   <li>/order/list - 拉取订单列表</li>
 *   <li>/order/detail - 查询订单详情（含 SKU、收货地址）</li>
 *   <li>/logistics/ship - 上传发货信息与物流单号</li>
 * </ol>
 * 鉴权方式：appKey + appSecret 生成 sign，请求头透传 access_token。
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link TemuMockClient} 离线模拟</li>
 *   <li>{@code !mock}：{@link TemuRealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface TemuClient {

    /**
     * 拉取 Temu 近 24 小时新订单。
     *
     * @param shopId 店铺 ID
     * @return Temu 订单列表
     */
    List<UnifiedOrder> fetchRecentOrders(Long shopId);

    /**
     * 向 Temu 回传发货信息。
     */
    boolean markShipped(String platformOrderNo, String trackingNo);
}
