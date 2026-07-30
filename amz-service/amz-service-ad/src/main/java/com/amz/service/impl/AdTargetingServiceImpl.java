package com.amz.service.impl;

import com.amz.mapper.AdTargetingMapper;
import com.amz.model.AdTargeting;
import com.amz.service.AdTargetingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SD 受众定向服务实现。
 */
@Service
public class AdTargetingServiceImpl implements AdTargetingService {

    @Autowired
    private AdTargetingMapper adTargetingMapper;

    @Override
    public AdTargeting createTargeting(AdTargeting targeting) {
        if (targeting.getImpressions() == null) {
            targeting.setImpressions(0L);
        }
        if (targeting.getClicks() == null) {
            targeting.setClicks(0L);
        }
        adTargetingMapper.insert(targeting);
        return targeting;
    }

    @Override
    public AdTargeting updateTargeting(AdTargeting targeting) {
        adTargetingMapper.updateById(targeting);
        return targeting;
    }

    @Override
    public List<AdTargeting> listByCampaign(String campaignId, String targetingType) {
        LambdaQueryWrapper<AdTargeting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdTargeting::getCampaignId, campaignId);
        if (targetingType != null && !targetingType.isBlank()) {
            wrapper.eq(AdTargeting::getTargetingType, targetingType);
        }
        wrapper.orderByDesc(AdTargeting::getId);
        return adTargetingMapper.selectList(wrapper);
    }

    @Override
    public void delete(Long id) {
        adTargetingMapper.deleteById(id);
    }
}
