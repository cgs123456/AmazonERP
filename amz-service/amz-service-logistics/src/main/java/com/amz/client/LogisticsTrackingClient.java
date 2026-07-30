package com.amz.client;

import com.amz.model.TrackingEvent;

import java.util.List;

/**
 * 物流轨迹查询客户端接口。
 * <p>
 * 生产环境对接路径：
 * <ul>
 *   <li>17track API（多承运商聚合查询）</li>
 *   <li>各承运商官方 API：COSCO/Maersk（海运）、DHL/FedEx/UPS（快递）</li>
 *   <li>Amazon FBA inbound API（货件状态同步）</li>
 * </ul>
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link LogisticsTrackingMockClient} 离线模拟</li>
 *   <li>{@code !mock}：{@link LogisticsTrackingRealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface LogisticsTrackingClient {

    /**
     * 根据运单号查询物流轨迹。
     *
     * @param trackingNo 运单号
     * @param carrier    承运商
     * @return 轨迹点列表（按时间倒序）
     */
    List<TrackingEvent> queryTracking(String trackingNo, String carrier);
}
