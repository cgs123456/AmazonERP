package com.amz.service;

import com.amz.model.AdCreative;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

/**
 * SB 广告素材管理服务接口。
 * 支持素材类型：VIDEO / IMAGE / STORE_SPOTLIGHT / CUSTOM_HEADLINE
 */
public interface AdCreativeService {

    /**
     * 在指定店铺的活动下创建广告素材。
     */
    AdCreative createCreative(Long shopId, AdCreative creative);

    /**
     * 更新指定店铺的广告素材。
     */
    AdCreative updateCreative(Long shopId, AdCreative creative);

    /**
     * 查询指定店铺、指定活动的素材列表。
     */
    PageResult<AdCreative> listByCampaign(Long shopId, String campaignId, PageRequest page);

    /**
     * 审核指定店铺的素材：PENDING → APPROVED / REJECTED。
     */
    AdCreative review(Long shopId, Long id, String status);
}
