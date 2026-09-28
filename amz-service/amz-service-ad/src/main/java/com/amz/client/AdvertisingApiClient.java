package com.amz.client;

import com.amz.model.AdCampaign;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;

import java.math.BigDecimal;
import java.util.List;

/**
 * Amazon Advertising API 客户端接口。
 * <p>
 * 接口以店铺为最小隔离边界。实现可使用离线 mock，或通过 LWA refresh token
 * 与 Advertising API profile 调用真实 v3 API。
 */
public interface AdvertisingApiClient {

    List<AdKeyword> listKeywords(Long shopId, String campaignId);

    boolean updateKeywordBid(Long shopId, Long keywordId, BigDecimal newBid);

    List<AdCampaign> listCampaigns(Long shopId);

    List<AdReport> getReports(Long shopId, String startDate, String endDate);
}
