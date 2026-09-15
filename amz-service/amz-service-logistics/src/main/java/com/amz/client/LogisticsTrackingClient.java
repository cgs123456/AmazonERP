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
     * 该客户端当前是否具备真实取数能力。
     * <p>
     * 供调度层在发起批量拉取前判断：未配置凭证时直接跳过，避免
     * 「每轮调度对每个货件刷一条 warn 日志」这种既无产出又掩盖真实告警的空转。
     * 默认返回 true（模拟实现始终可用）。
     *
     * @return true 表示调用 {@link #queryTracking(String, String)} 可能返回真实数据
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 根据运单号查询物流轨迹。
     * <p>
     * <b>实现约定：</b>返回的轨迹点只需填入承运商视角的原始信息
     * （{@code eventStatus} 填承运商状态原文、{@code eventTime} 填原始时间串、
     * 经纬度可缺省），<b>不要在此层做状态映射与时间归一化</b>——
     * 这两件事统一由落库核心完成，否则导入与拉取两条入口会各自解出不同状态。
     *
     * @param trackingNo 运单号
     * @param carrier    承运商
     * @return 轨迹点列表（按时间倒序）；无数据或无凭证时返回空列表而非抛异常
     */
    List<TrackingEvent> queryTracking(String trackingNo, String carrier);
}
