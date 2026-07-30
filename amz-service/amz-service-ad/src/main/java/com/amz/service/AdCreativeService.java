package com.amz.service;

import com.amz.model.AdCreative;

import java.util.List;

/**
 * SB 广告素材管理服务接口。
 * 支持素材类型：VIDEO / IMAGE / STORE_SPOTLIGHT / CUSTOM_HEADLINE
 */
public interface AdCreativeService {

    /**
     * 创建广告素材。
     */
    AdCreative createCreative(AdCreative creative);

    /**
     * 更新广告素材。
     */
    AdCreative updateCreative(AdCreative creative);

    /**
     * 查询活动的素材列表。
     */
    List<AdCreative> listByCampaign(String campaignId);

    /**
     * 审核素材：PENDING → APPROVED / REJECTED。
     */
    AdCreative review(Long id, String status);
}
