package com.amz.client;

import com.amz.model.UnifiedOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * TikTok Shop 开放平台真实客户端骨架。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 待实现项：
 * <ul>
 *   <li>appKey + appSecret 换取 access_token</li>
 *   <li>/orders/search 订单搜索</li>
 *   <li>/fulfillment/{order_id}/ship 上传物流单号</li>
 * </ul>
 * 在真实 API 未对接前，{@link #fetchRecentOrders(Long)} 和
 * {@link #markShipped(String, String)} 不再抛出
 * {@link UnsupportedOperationException}，改为打 warn 日志并返回 mock 默认值，
 * 避免上游因未对接 API 抛异常中断流程。
 */
@Slf4j
@Component
@Profile("!mock")
public class TikTokRealClient extends AbstractPlatformClient implements TikTokClient {

    @Value("${platform.tiktok.app-key:}")
    private String appKey;

    @Value("${platform.tiktok.app-secret:}")
    private String appSecret;

    /**
     * 真实实现中可注入 RestTemplate / OkHttp；当前为骨架，未配置 Bean 时不影响编译。
     */
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    protected String getPlatform() {
        return PLATFORM_TIKTOK;
    }

    @Override
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        // 真实 TikTok Shop API 待对接：打 warn 日志，返回空列表降级，避免抛异常中断订单同步流程。
        log.warn("真实 TikTok Shop API 待对接，使用 mock 数据降级：fetchRecentOrders 返回空列表 shopId={} appKey={}",
                shopId, mask(appKey));
        return Collections.emptyList();
    }

    @Override
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        // 真实 TikTok Shop API 待对接：打 warn 日志，返回 false 降级，避免抛异常；
        // 上游可据此决定是否重试，而非强制中断。
        log.warn("真实 TikTok Shop API 待对接，使用 mock 数据降级：markShipped 返回 false orderNo={} trackingNo={}",
                platformOrderNo, trackingNo);
        return false;
    }
}
