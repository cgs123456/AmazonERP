package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdCreativeMapper;
import com.amz.model.AdCreative;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.AdCreativeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SB 广告素材服务实现。
 */
@Service
public class AdCreativeServiceImpl implements AdCreativeService {

    @Autowired
    private AdCreativeMapper adCreativeMapper;

    @Autowired
    private AdTenantGuard tenantGuard;

    @Override
    public AdCreative createCreative(Long shopId, AdCreative creative) {
        if (creative == null) {
            throw new AttrIsNullException("素材数据不能为空");
        }
        tenantGuard.requireCampaign(shopId, creative.getCampaignId());
        creative.setId(null);
        creative.setShopId(shopId);
        if (creative.getStatus() == null) {
            creative.setStatus("PENDING");
        }
        adCreativeMapper.insert(creative);
        return creative;
    }

    @Override
    public AdCreative updateCreative(Long shopId, AdCreative creative) {
        if (creative == null || creative.getId() == null) {
            throw new AttrIsNullException("素材ID不能为空");
        }
        tenantGuard.requireShopAccess(shopId);
        AdCreative existing = adCreativeMapper.selectById(creative.getId());
        if (existing == null) {
            throw new CodeErrorException("素材不存在或无权访问");
        }
        tenantGuard.requireResourceShop(shopId, existing.getShopId());

        String targetCampaignId = creative.getCampaignId() == null
                ? existing.getCampaignId() : creative.getCampaignId();
        tenantGuard.requireCampaign(shopId, targetCampaignId);
        creative.setCampaignId(targetCampaignId);
        creative.setShopId(shopId);

        int affected = adCreativeMapper.update(creative,
                new LambdaUpdateWrapper<AdCreative>()
                        .eq(AdCreative::getId, creative.getId())
                        .eq(AdCreative::getShopId, shopId));
        if (affected == 0) {
            throw new CodeErrorException("素材不存在或无权访问");
        }
        return creative;
    }

    @Override
    public PageResult<AdCreative> listByCampaign(Long shopId, String campaignId, PageRequest page) {
        tenantGuard.requireCampaign(shopId, campaignId);
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<AdCreative> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdCreative::getShopId, shopId)
               .eq(AdCreative::getCampaignId, campaignId);
        if (req.hasCursor()) {
            wrapper.lt(AdCreative::getId, req.cursorId());
        }
        wrapper.orderByDesc(AdCreative::getId)
               .last("LIMIT " + req.probeSize());
        List<AdCreative> rows = adCreativeMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), creative -> PageRequest.encodeCursor(creative.getId()));
    }

    @Override
    public AdCreative review(Long shopId, Long id, String status) {
        if (id == null) {
            throw new AttrIsNullException("素材ID不能为空");
        }
        tenantGuard.requireShopAccess(shopId);
        AdCreative creative = adCreativeMapper.selectById(id);
        if (creative == null) {
            throw new CodeErrorException("素材不存在或无权访问");
        }
        tenantGuard.requireResourceShop(shopId, creative.getShopId());
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new CodeErrorException("审核状态仅支持 APPROVED / REJECTED");
        }
        creative.setStatus(status);
        int affected = adCreativeMapper.update(creative,
                new LambdaUpdateWrapper<AdCreative>()
                        .eq(AdCreative::getId, id)
                        .eq(AdCreative::getShopId, shopId));
        if (affected == 0) {
            throw new CodeErrorException("素材不存在或无权访问");
        }
        return creative;
    }
}
