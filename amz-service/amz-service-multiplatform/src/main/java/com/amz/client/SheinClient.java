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
public interface SheinClient {

    /**
     * 拉取 Shein 近 24 小时新订单。
     */
    List<UnifiedOrder> fetchRecentOrders(Long shopId);

    /**
     * 向 Shein 回传发货信息。
     */
    boolean markShipped(String platformOrderNo, String trackingNo);
}
