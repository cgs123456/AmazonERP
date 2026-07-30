package com.amz.client;

import com.amz.model.UnifiedOrder;

import java.util.List;

/**
 * TikTok Shop 开放平台客户端接口。
 * <p>
 * 生产环境对接路径（参考 TikTok Shop API）：
 * <ol>
 *   <li>/orders/search - 订单搜索（按时间/状态过滤）</li>
 *   <li>/orders/{order_id} - 订单详情</li>
 *   <li>/fulfillment/{order_id}/ship - 上传物流单号</li>
 * </ol>
 * 鉴权方式：appKey + appSecret 换取 access_token，请求头透传。
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link TikTokMockClient} 离线模拟</li>
 *   <li>{@code !mock}：{@link TikTokRealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface TikTokClient {

    /**
     * 拉取 TikTok Shop 近 24 小时新订单。
     */
    List<UnifiedOrder> fetchRecentOrders(Long shopId);

    /**
     * 向 TikTok Shop 回传发货信息。
     */
    boolean markShipped(String platformOrderNo, String trackingNo);
}
