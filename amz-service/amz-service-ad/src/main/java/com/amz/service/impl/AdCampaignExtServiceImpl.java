package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.model.AdCampaignExt;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.AdCampaignExtService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 广告活动扩展服务实现（支持 SP/SB/SD/DSP）。
 */
@Service
public class AdCampaignExtServiceImpl implements AdCampaignExtService {

    private static final Set<String> STATUSES = Set.of("ENABLED", "PAUSED", "ARCHIVED");

    @Autowired
    private AdCampaignExtMapper campaignExtMapper;

    @Autowired
    private AdTenantGuard tenantGuard;

    @Override
    public AdCampaignExt createCampaign(Long shopId, AdCampaignExt campaign) {
        tenantGuard.requireShopAccess(shopId);
        requireCampaignBody(campaign);
        requireBodyShopMatches(shopId, campaign.getShopId());
        validateCreateFields(campaign);
        campaign.setShopId(shopId);
        applyCreateDefaults(campaign);
        campaignExtMapper.insert(campaign);
        return campaign;
    }

    @Override
    public AdCampaignExt updateCampaign(Long shopId, AdCampaignExt campaign) {
        tenantGuard.requireShopAccess(shopId);
        if (campaign == null || campaign.getId() == null) {
            throw new AttrIsNullException("活动ID不能为空");
        }
        AdCampaignExt existing = campaignExtMapper.selectById(campaign.getId());
        if (existing == null) {
            throw new CodeErrorException("广告活动不存在或无权访问");
        }
        tenantGuard.requireResourceShop(shopId, existing.getShopId());
        requireBodyShopMatches(shopId, campaign.getShopId());
        if (campaign.getCampaignId() != null
                && !campaign.getCampaignId().equals(existing.getCampaignId())) {
            throw new CodeErrorException("活动ID不可修改");
        }
        if (campaign.getStatus() != null) {
            validateStatus(campaign.getStatus());
        }
        campaign.setCampaignId(existing.getCampaignId());
        campaign.setShopId(shopId);

        int affected = campaignExtMapper.update(campaign,
                new LambdaUpdateWrapper<AdCampaignExt>()
                        .eq(AdCampaignExt::getId, campaign.getId())
                        .eq(AdCampaignExt::getShopId, shopId));
        if (affected == 0) {
            throw new CodeErrorException("广告活动不存在或无权访问");
        }
        return campaign;
    }

    @Override
    public PageResult<AdCampaignExt> listCampaigns(Long shopId, String adType, PageRequest page) {
        tenantGuard.requireShopAccess(shopId);
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<AdCampaignExt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdCampaignExt::getShopId, shopId);
        if (adType != null && !adType.isBlank()) {
            wrapper.eq(AdCampaignExt::getAdType, adType);
        }
        if (req.hasCursor()) {
            wrapper.lt(AdCampaignExt::getId, req.cursorId());
        }
        wrapper.orderByDesc(AdCampaignExt::getId)
               .last("LIMIT " + req.probeSize());
        List<AdCampaignExt> rows = campaignExtMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), campaign -> PageRequest.encodeCursor(campaign.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AdCampaignExt> batchCreate(Long shopId, List<AdCampaignExt> campaigns) {
        tenantGuard.requireShopAccess(shopId);
        if (campaigns == null || campaigns.isEmpty()) {
            return new ArrayList<>();
        }
        for (AdCampaignExt campaign : campaigns) {
            requireCampaignBody(campaign);
            requireBodyShopMatches(shopId, campaign.getShopId());
            validateCreateFields(campaign);
        }
        for (AdCampaignExt campaign : campaigns) {
            campaign.setShopId(shopId);
            applyCreateDefaults(campaign);
            campaignExtMapper.insert(campaign);
        }
        return campaigns;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AdCampaignExt> batchUpdateStatus(Long shopId, List<Long> ids, String status) {
        tenantGuard.requireShopAccess(shopId);
        validateStatus(status);
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        // 租户条件下推到单条 UPDATE，避免参数中混入其他店铺 id 时跨店更新。
        campaignExtMapper.batchUpdateStatusByIds(shopId, ids, status);

        LambdaQueryWrapper<AdCampaignExt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdCampaignExt::getShopId, shopId)
               .in(AdCampaignExt::getId, ids);
        List<AdCampaignExt> updated = campaignExtMapper.selectList(wrapper);
        if (updated == null || updated.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, AdCampaignExt> byId = new LinkedHashMap<>();
        for (AdCampaignExt campaign : updated) {
            byId.put(campaign.getId(), campaign);
        }
        List<AdCampaignExt> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            AdCampaignExt campaign = byId.get(id);
            if (campaign != null) {
                ordered.add(campaign);
            }
        }
        return ordered;
    }

    private static void requireCampaignBody(AdCampaignExt campaign) {
        if (campaign == null) {
            throw new AttrIsNullException("活动数据不能为空");
        }
    }

    private static void requireBodyShopMatches(Long shopId, Long bodyShopId) {
        if (bodyShopId != null && !shopId.equals(bodyShopId)) {
            throw new CodeErrorException("活动归属与请求店铺不一致");
        }
    }

    private static void validateCreateFields(AdCampaignExt campaign) {
        if (campaign.getCampaignId() == null || campaign.getCampaignId().isBlank()) {
            throw new AttrIsNullException("活动ID不能为空");
        }
        if (campaign.getCampaignName() == null || campaign.getCampaignName().isBlank()) {
            throw new AttrIsNullException("活动名称不能为空");
        }
        if (campaign.getAdType() == null || campaign.getAdType().isBlank()) {
            throw new AttrIsNullException("广告类型不能为空");
        }
        if (campaign.getStatus() != null) {
            validateStatus(campaign.getStatus());
        }
    }

    private static void validateStatus(String status) {
        if (status == null || !STATUSES.contains(status)) {
            throw new CodeErrorException("活动状态仅支持 ENABLED / PAUSED / ARCHIVED");
        }
    }

    private static void applyCreateDefaults(AdCampaignExt campaign) {
        if (campaign.getStatus() == null) {
            campaign.setStatus("ENABLED");
        }
        if (campaign.getImpressions() == null) {
            campaign.setImpressions(0L);
        }
        if (campaign.getClicks() == null) {
            campaign.setClicks(0L);
        }
    }
}
