package com.amz.client;

import com.amz.model.AdKeyword;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Amazon Advertising API 真实客户端骨架。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 待实现项：
 * <ul>
 *   <li>LWA Token 获取（通过 Feign 调用 amz-service-spapi）</li>
 *   <li>GET /sp/keywords 拉取关键词</li>
 *   <li>PUT /sp/keywords/bid 修改竞价</li>
 * </ul>
 * 在真实 API 未对接前，{@link #listKeywords(Long, String)} 和
 * {@link #updateKeywordBid(Long, BigDecimal)} 不再抛出
 * {@link UnsupportedOperationException}，改为打 warn 日志并返回 mock 默认值，
 * 避免上游因未对接 API 抛异常中断广告投放优化流程。
 */
@Slf4j
@Component
@Profile("!mock")
public class AdvertisingApiRealClient implements AdvertisingApiClient {

    @Value("${advertising.api-endpoint:https://advertising-api.amazon.com}")
    private String apiEndpoint;

    @Value("${advertising.profile-id:}")
    private String profileId;

    /**
     * 真实实现中可注入 RestTemplate / OkHttp；当前为骨架，未配置 Bean 时不影响编译。
     */
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public List<AdKeyword> listKeywords(Long shopId, String campaignId) {
        // 真实 Amazon Advertising API 待对接：打 warn 日志，返回空列表降级，避免抛异常中断关键词拉取流程。
        log.warn("真实 Amazon Advertising API 待对接，使用 mock 数据降级：listKeywords 返回空列表 shopId={} campaignId={} profileId={}",
                shopId, campaignId, profileId);
        return Collections.emptyList();
    }

    @Override
    public boolean updateKeywordBid(Long keywordId, BigDecimal newBid) {
        // 真实 Amazon Advertising API 待对接：打 warn 日志，返回 false 降级，避免抛异常；
        // 上游可据此决定是否重试，而非强制中断竞价优化流程。
        log.warn("真实 Amazon Advertising API 待对接，使用 mock 数据降级：updateKeywordBid 返回 false keywordId={} newBid={}",
                keywordId, newBid);
        return false;
    }
}
