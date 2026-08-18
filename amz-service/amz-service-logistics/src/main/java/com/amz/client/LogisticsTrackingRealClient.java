package com.amz.client;

import com.amz.model.TrackingEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 物流轨迹查询真实客户端骨架。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 待实现项：
 * <ul>
 *   <li>17track API 多承运商聚合查询</li>
 *   <li>承运商官方 API 适配（COSCO/Maersk/DHL/FedEx/UPS）</li>
 *   <li>Amazon FBA inbound API 货件状态同步</li>
 * </ul>
 * 在真实 API 未对接前，{@link #queryTracking(String, String)} 不再抛出
 * {@link UnsupportedOperationException}，改为打 warn 日志并返回空列表降级，
 * 避免上游因未对接 API 抛异常中断轨迹查询流程。
 */
@Slf4j
@Component
@Profile("!mock")
public class LogisticsTrackingRealClient implements LogisticsTrackingClient {

    // 说明：原此处持有一个从未被读取的 new RestTemplate()（死代码且无超时配置），已移除。
    // 真实对接时请注入 com.amz.http.ResilientHttpClient（target=17track / 各承运商），
    // 不要再自建 RestTemplate。

    @Override
    public List<TrackingEvent> queryTracking(String trackingNo, String carrier) {
        // 真实物流轨迹 API 待对接：打 warn 日志，返回空列表降级，避免抛异常中断轨迹查询流程。
        log.warn("真实物流轨迹 API 待对接，使用 mock 数据降级：queryTracking 返回空列表 trackingNo={} carrier={}",
                trackingNo, carrier);
        return Collections.emptyList();
    }
}
