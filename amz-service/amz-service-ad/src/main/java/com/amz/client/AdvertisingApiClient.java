package com.amz.client;

import com.amz.model.AdKeyword;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Amazon Advertising API 模拟客户端。
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
 * 当前为离线模拟实现，返回构造数据，保证项目可独立运行。
 */
@Slf4j
@Component
public class AdvertisingApiClient {

    /**
     * 拉取店铺下某活动的关键词列表。
     *
     * @param shopId     店铺 ID
     * @param campaignId 活动 ID（null 表示该店铺全部活动）
     */
    public List<AdKeyword> listKeywords(Long shopId, String campaignId) {
        // 模拟：实际应调用 GET /sp/keywords?campaignId=...
        List<AdKeyword> list = new ArrayList<>();
        AdKeyword kw = new AdKeyword();
        kw.setId(1L);
        kw.setCampaignId(campaignId != null ? campaignId : "mock-campaign-1");
        kw.setShopId(shopId);
        kw.setKeyword("wireless earbuds");
        kw.setMatchType("EXACT");
        kw.setBid(new BigDecimal("1.20"));
        kw.setState("ENABLED");
        list.add(kw);
        log.debug("AdvertisingApiClient.listKeywords 模拟返回 {} 条", list.size());
        return list;
    }

    /**
     * 修改关键词竞价。
     *
     * @param keywordId 关键词 ID
     * @param newBid    新竞价
     */
    public boolean updateKeywordBid(Long keywordId, BigDecimal newBid) {
        // 模拟：实际应调用 PUT /sp/keywords
        log.info("AdvertisingApiClient.updateKeywordBid 模拟：keywordId={} newBid={}", keywordId, newBid);
        return true;
    }
}
