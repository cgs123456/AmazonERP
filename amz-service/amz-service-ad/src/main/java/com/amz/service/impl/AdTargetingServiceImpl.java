package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdTargetingMapper;
import com.amz.model.AdTargeting;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.AdTargetingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    @Autowired
    private AdTenantGuard tenantGuard;

    @Override
    public AdTargeting createTargeting(Long shopId, AdTargeting targeting) {
        if (targeting == null) {
            throw new AttrIsNullException("定向数据不能为空");
        }
        tenantGuard.requireCampaign(shopId, targeting.getCampaignId());
        targeting.setId(null);
        targeting.setShopId(shopId);
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
    public AdTargeting updateTargeting(Long shopId, AdTargeting targeting) {
        if (targeting == null || targeting.getId() == null) {
            throw new AttrIsNullException("定向ID不能为空");
        }
        tenantGuard.requireShopAccess(shopId);
        AdTargeting existing = adTargetingMapper.selectById(targeting.getId());
        if (existing == null) {
            throw new CodeErrorException("定向规则不存在或无权访问");
        }
        tenantGuard.requireResourceShop(shopId, existing.getShopId());

        String targetCampaignId = targeting.getCampaignId() == null
                ? existing.getCampaignId() : targeting.getCampaignId();
        tenantGuard.requireCampaign(shopId, targetCampaignId);
        targeting.setCampaignId(targetCampaignId);
        targeting.setShopId(shopId);

        int affected = adTargetingMapper.update(targeting,
                new LambdaUpdateWrapper<AdTargeting>()
                        .eq(AdTargeting::getId, targeting.getId())
                        .eq(AdTargeting::getShopId, shopId));
        if (affected == 0) {
            throw new CodeErrorException("定向规则不存在或无权访问");
        }
        return targeting;
    }

    @Override
    public PageResult<AdTargeting> listByCampaign(Long shopId, String campaignId,
                                                   String targetingType, PageRequest page) {
        tenantGuard.requireCampaign(shopId, campaignId);
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<AdTargeting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdTargeting::getShopId, shopId)
               .eq(AdTargeting::getCampaignId, campaignId);
        if (targetingType != null && !targetingType.isBlank()) {
            wrapper.eq(AdTargeting::getTargetingType, targetingType);
        }
        if (req.hasCursor()) {
            wrapper.lt(AdTargeting::getId, req.cursorId());
        }
        wrapper.orderByDesc(AdTargeting::getId)
               .last("LIMIT " + req.probeSize());
        List<AdTargeting> rows = adTargetingMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), targeting -> PageRequest.encodeCursor(targeting.getId()));
    }

    @Override
    public void delete(Long shopId, Long id) {
        if (id == null) {
            throw new AttrIsNullException("定向ID不能为空");
        }
        tenantGuard.requireShopAccess(shopId);
        AdTargeting existing = adTargetingMapper.selectById(id);
        if (existing == null) {
            throw new CodeErrorException("定向规则不存在或无权访问");
        }
        tenantGuard.requireResourceShop(shopId, existing.getShopId());
        int affected = adTargetingMapper.delete(
                new LambdaQueryWrapper<AdTargeting>()
                        .eq(AdTargeting::getId, id)
                        .eq(AdTargeting::getShopId, shopId));
        if (affected == 0) {
            throw new CodeErrorException("定向规则不存在或无权访问");
        }
    }
}
