package com.amz.client;

import com.amz.model.AdKeyword;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Amazon Advertising API 模拟客户端。
 * <p>
 * 离线模拟实现，返回构造数据，保证项目可独立运行。
 * 仅在 {@code spring.profiles.active=mock} 时生效。
 */
@Slf4j
@Component
@Profile("mock")
public class AdvertisingApiMockClient implements AdvertisingApiClient {

    @Override
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
        log.debug("AdvertisingApiMockClient.listKeywords 模拟返回 {} 条", list.size());
        return list;
    }

    @Override
    public boolean updateKeywordBid(Long keywordId, BigDecimal newBid) {
        // 模拟：实际应调用 PUT /sp/keywords
        log.info("AdvertisingApiMockClient.updateKeywordBid 模拟：keywordId={} newBid={}", keywordId, newBid);
        return true;
    }
}
