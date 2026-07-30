package com.amz.service.impl;

import com.amz.mapper.AdCampaignExtMapper;
import com.amz.model.AdCampaignExt;
import com.amz.service.AdCampaignExtService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 广告活动扩展服务实现（支持 SP/SB/SD/DSP）。
 */
@Service
public class AdCampaignExtServiceImpl implements AdCampaignExtService {

    @Autowired
    private AdCampaignExtMapper campaignExtMapper;

    @Override
    public AdCampaignExt createCampaign(AdCampaignExt campaign) {
        if (campaign.getStatus() == null) {
            campaign.setStatus("ENABLED");
        }
        if (campaign.getImpressions() == null) {
            campaign.setImpressions(0L);
        }
        if (campaign.getClicks() == null) {
            campaign.setClicks(0L);
        }
        campaignExtMapper.insert(campaign);
        return campaign;
    }

    @Override
    public AdCampaignExt updateCampaign(AdCampaignExt campaign) {
        campaignExtMapper.updateById(campaign);
        return campaign;
    }

    @Override
    public List<AdCampaignExt> listCampaigns(Long shopId, String adType) {
        LambdaQueryWrapper<AdCampaignExt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdCampaignExt::getShopId, shopId);
        if (adType != null && !adType.isBlank()) {
            wrapper.eq(AdCampaignExt::getAdType, adType);
        }
        wrapper.orderByDesc(AdCampaignExt::getId);
        return campaignExtMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AdCampaignExt> batchCreate(List<AdCampaignExt> campaigns) {
        for (AdCampaignExt c : campaigns) {
            createCampaign(c);
        }
        return campaigns;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AdCampaignExt> batchUpdateStatus(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        // 修复：原实现循环内 selectById + updateById，N 个 ID 触发 2N 次查询。
        // 现改为单条 UPDATE ... WHERE id IN (...)，一次完成批量更新。
        campaignExtMapper.batchUpdateStatusByIds(ids, status);
        // 返回更新后的记录（selectBatchIds 单次查询）
        List<AdCampaignExt> updated = campaignExtMapper.selectBatchIds(ids);
        // 保持原返回顺序与入参 ids 顺序一致
        if (updated == null || updated.isEmpty()) {
            return Collections.emptyList();
        }
        return updated;
    }
}
