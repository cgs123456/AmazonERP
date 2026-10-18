package com.amz.client;

import com.amz.model.UnifiedOrder;

import java.util.List;

/**
 * Shein 开放平台客户端接口。
 * <p>
 * 生产环境对接路径（参考 Shein Open Platform）：
 * <ol>
 *   <li>/open/orders/list - 订单列表</li>
 *   <li>/open/orders/detail - 订单详情</li>
 *   <li>/open/logistics/upload - 物流单号回传</li>
 * </ol>
 * 鉴权方式：appKey + appSecret 生成 sign。
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link SheinMockClient} 离线模拟</li>
 *   <li>{@code !mock}：{@link SheinRealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface SheinClient extends PlatformDataClient {

    /**
     * 拉取 Shein 近 24 小时新订单。
     */
    List<UnifiedOrder> fetchRecentOrders(Long shopId);

    /**
     * 向 Shein 回传发货信息。
     *
     * @param shopId 发货订单所属店铺。必须由调用方传入，不能省略：它决定用哪家店的
     *               平台凭证发这个请求。省略会让客户端回退到「该平台首个账号」，
     *               等于用一个租户的身份替另一个租户发货（§S33.92）。
     */
    boolean markShipped(Long shopId, String platformOrderNo, String trackingNo);
}
