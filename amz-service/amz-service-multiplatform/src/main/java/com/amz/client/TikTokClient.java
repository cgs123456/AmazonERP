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
public interface TikTokClient extends PlatformDataClient {

    /**
     * 拉取 TikTok Shop 近 24 小时新订单。
     */
    List<UnifiedOrder> fetchRecentOrders(Long shopId);

    /**
     * 向 TikTok Shop 回传发货信息。
     *
     * @param shopId 发货订单所属店铺。必须由调用方传入，不能省略：它决定用哪家店的
     *               平台凭证发这个请求。省略会让客户端回退到「该平台首个账号」，
     *               等于用一个租户的身份替另一个租户发货（§S33.92）。
     */
    boolean markShipped(Long shopId, String platformOrderNo, String trackingNo);
}
