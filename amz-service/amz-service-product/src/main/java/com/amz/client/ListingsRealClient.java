package com.amz.client;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

/**
 * Amazon SP-API Listings/Feeds 真实客户端骨架。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 待实现项：
 * <ul>
 *   <li>LWA Token 获取（通过 Feign 调用 amz-service-spapi 复用刷新机制）</li>
 *   <li>AWS Sig V4 签名</li>
 *   <li>POST /feeds/2021-06-30/feeds 提交 Feed</li>
 *   <li>GET /feeds/2021-06-30/feeds/{feedId} 查询处理状态</li>
 * </ul>
 * 在真实 API 未对接前，{@link #submitFeed(Long, String, String)} 和
 * {@link #getFeedStatus(Long, String)} 不再抛出
 * {@link UnsupportedOperationException}，改为打 warn 日志并返回 mock 占位结果，
 * 避免上游因未对接 API 抛异常中断流程。
 */
@Slf4j
@Component
@Profile("!mock")
public class ListingsRealClient implements ListingsClient {

    /**
     * 真实实现中可注入 RestTemplate / OkHttp；当前为骨架，未配置 Bean 时不影响编译。
     */
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String submitFeed(Long shopId, String marketplaceId, String jsonlContent) {
        // 真实 SP-API Listings/Feeds 待对接：打 warn 日志，返回 MOCK_FEED_ 前缀占位 feedSubmissionId，
        // 避免上游因未对接 API 抛异常中断流程。
        String feedSubmissionId = "MOCK_FEED_" + UUID.randomUUID();
        log.warn("真实 SP-API Listings Feed 待对接，使用 mock 数据降级：shopId={} marketplaceId={} → feedSubmissionId={}",
                shopId, marketplaceId, feedSubmissionId);
        return feedSubmissionId;
    }

    @Override
    public JsonObject getFeedStatus(Long shopId, String feedSubmissionId) {
        // 真实 SP-API Feeds 状态查询待对接：返回 DONE 占位状态，避免上游抛异常。
        log.warn("真实 SP-API Listings Feed 状态查询待对接，使用 mock 数据降级：shopId={} feedSubmissionId={}",
                shopId, feedSubmissionId);
        JsonObject result = new JsonObject();
        result.addProperty("feedSubmissionId", feedSubmissionId);
        result.addProperty("processingStatus", "DONE");
        return result;
    }
}
