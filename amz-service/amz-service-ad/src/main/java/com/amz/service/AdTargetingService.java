package com.amz.service;

import com.amz.model.AdTargeting;

import java.util.List;

/**
 * SD 受众定向管理服务接口。
 * 定向类型：CONTEXTUAL / REMARKETING / AUDIENCE / LOOKALIKE
 */
public interface AdTargetingService {

    /**
     * 创建定向规则。
     */
    AdTargeting createTargeting(AdTargeting targeting);

    /**
     * 更新定向规则。
     */
    AdTargeting updateTargeting(AdTargeting targeting);

    /**
     * 查询活动的定向规则列表。
     */
    List<AdTargeting> listByCampaign(String campaignId, String targetingType);

    /**
     * 删除定向规则。
     */
    void delete(Long id);
}
