package com.amz.client;

import com.amz.model.AdCampaign;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
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

    @Override
    public List<AdCampaign> listCampaigns(Long shopId) {
        List<AdCampaign> list = new ArrayList<>();
        AdCampaign c = new AdCampaign();
        c.setId(1L);
        c.setCampaignId("mock-campaign-1");
        c.setShopId(shopId);
        c.setName("Mock SP Campaign");
        c.setCampaignType("SP");
        c.setState("ENABLED");
        c.setDailyBudget(new BigDecimal("50.00"));
        c.setBiddingStrategy("LEGACY_FOR_SALES");
        list.add(c);
        log.debug("AdvertisingApiMockClient.listCampaigns 模拟返回 {} 条", list.size());
        return list;
    }

    @Override
    public List<AdReport> getReports(Long shopId, String startDate, String endDate) {
        List<AdReport> list = new ArrayList<>();
        AdReport r = new AdReport();
        r.setCampaignId("mock-campaign-1");
        r.setKeyword("wireless earbuds");
        r.setImpressions(10000L);
        r.setClicks(250L);
        r.setCost(new BigDecimal("120.50"));
        r.setSales(new BigDecimal("800.00"));
        r.setOrders(15);
        r.setAcos(new BigDecimal("15.06"));
        r.setRoas(new BigDecimal("6.64"));
        r.setCtr(new BigDecimal("2.50"));
        r.setCr(new BigDecimal("6.00"));
        r.setCpc(new BigDecimal("0.48"));
        list.add(r);
        log.debug("AdvertisingApiMockClient.getReports 模拟返回 {} 条 period={}~{}", list.size(), startDate, endDate);
        return list;
    }
}
