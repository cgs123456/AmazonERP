package com.amz.service;

import com.amz.model.AdTargeting;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

/**
 * SD 受众定向管理服务接口。
 * 定向类型：CONTEXTUAL / REMARKETING / AUDIENCE / LOOKALIKE
 */
public interface AdTargetingService {

    /**
     * 在指定店铺的活动下创建定向规则。
     */
    AdTargeting createTargeting(Long shopId, AdTargeting targeting);

    /**
     * 更新指定店铺的定向规则。
     */
    AdTargeting updateTargeting(Long shopId, AdTargeting targeting);

    /**
     * 查询指定店铺、指定活动的定向规则列表。
     */
    PageResult<AdTargeting> listByCampaign(Long shopId, String campaignId,
                                           String targetingType, PageRequest page);

    /**
     * 删除指定店铺的定向规则。
     */
    void delete(Long shopId, Long id);
}
