package com.amz.service;

import com.amz.model.AdCampaignExt;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import java.util.List;

/**
 * 广告活动扩展服务接口（支持 SP/SB/SD/DSP 四种广告类型）。
 */
public interface AdCampaignExtService {

    /**
     * 在指定店铺下创建广告活动。
     */
    AdCampaignExt createCampaign(Long shopId, AdCampaignExt campaign);

    /**
     * 更新指定店铺的广告活动，禁止改写记录归属。
     */
    AdCampaignExt updateCampaign(Long shopId, AdCampaignExt campaign);

    /**
     * 查询店铺广告活动列表（按 adType 筛选，null 表示全部类型）。
     */
    PageResult<AdCampaignExt> listCampaigns(Long shopId, String adType, PageRequest page);

    /**
     * 在指定店铺下批量创建广告活动。
     */
    List<AdCampaignExt> batchCreate(Long shopId, List<AdCampaignExt> campaigns);

    /**
     * 批量更新指定店铺的活动状态（ENABLED / PAUSED / ARCHIVED）。
     */
    List<AdCampaignExt> batchUpdateStatus(Long shopId, List<Long> ids, String status);
}
