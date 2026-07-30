package com.amz.service;

import com.amz.model.AdCampaignExt;

import java.util.List;

/**
 * 广告活动扩展服务接口（支持 SP/SB/SD/DSP 四种广告类型）。
 */
public interface AdCampaignExtService {

    /**
     * 创建广告活动。
     */
    AdCampaignExt createCampaign(AdCampaignExt campaign);

    /**
     * 更新广告活动。
     */
    AdCampaignExt updateCampaign(AdCampaignExt campaign);

    /**
     * 查询店铺广告活动列表（按 adType 筛选，null 表示全部类型）。
     */
    List<AdCampaignExt> listCampaigns(Long shopId, String adType);

    /**
     * 批量创建广告活动。
     */
    List<AdCampaignExt> batchCreate(List<AdCampaignExt> campaigns);

    /**
     * 批量更新状态（ENABLED / PAUSED / ARCHIVED）。
     */
    List<AdCampaignExt> batchUpdateStatus(List<Long> ids, String status);
}
