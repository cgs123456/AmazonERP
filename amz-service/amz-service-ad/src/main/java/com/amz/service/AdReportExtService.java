package com.amz.service;

import com.amz.model.AdCampaignExt;

import java.util.List;
import java.util.Map;

/**
 * 广告综合报表服务接口（SP + SB + SD + DSP 汇总）。
 */
public interface AdReportExtService {

    /**
     * 查询店铺广告活动列表（按 adType 筛选）。
     * 返回的活动已包含 impressions / clicks / spend / sales / orders / acos / roas 指标。
     */
    List<AdCampaignExt> listReports(Long shopId, String adType);

    /**
     * 店铺整体汇总（全部广告类型聚合）。
     *
     * @return Map 包含：impressions / clicks / spend / sales / orders / acos / roas
     */
    Map<String, Object> getShopSummary(Long shopId);

    /**
     * 按广告类型汇总（返回 SP/SB/SD/DSP 各自的聚合指标）。
     */
    Map<String, Map<String, Object>> getSummaryByType(Long shopId);
}
