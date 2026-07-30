package com.amz.client;

import com.amz.model.AdKeyword;

import java.math.BigDecimal;
import java.util.List;

/**
 * Amazon Advertising API 客户端接口。
 * <p>
 * 生产环境对接路径：
 * <ol>
 *   <li>GET /v2/profiles 获取 profileId</li>
 *   <li>GET /v2/sp/campaigns 拉取广告活动</li>
 *   <li>GET /sp/keywords 拉取关键词</li>
 *   <li>PUT /sp/keywords/bid 下发竞价修改</li>
 * </ol>
 * LWA Token 通过 Feign 调用 amz-service-spapi 复用刷新机制。
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link AdvertisingApiMockClient} 离线模拟</li>
 *   <li>{@code !mock}：{@link AdvertisingApiRealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface AdvertisingApiClient {

    /**
     * 拉取店铺下某活动的关键词列表。
     *
     * @param shopId     店铺 ID
     * @param campaignId 活动 ID（null 表示该店铺全部活动）
     */
    List<AdKeyword> listKeywords(Long shopId, String campaignId);

    /**
     * 修改关键词竞价。
     *
     * @param keywordId 关键词 ID
     * @param newBid    新竞价
     */
    boolean updateKeywordBid(Long keywordId, BigDecimal newBid);
}
