package com.amz.service.impl;

import com.amz.mapper.AdCreativeMapper;
import com.amz.model.AdCreative;
import com.amz.service.AdCreativeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    @Override
    public AdCreative createCreative(AdCreative creative) {
        if (creative.getStatus() == null) {
            creative.setStatus("PENDING");
        }
        adCreativeMapper.insert(creative);
        return creative;
    }

    @Override
    public AdCreative updateCreative(AdCreative creative) {
        adCreativeMapper.updateById(creative);
        return creative;
    }

    @Override
    public List<AdCreative> listByCampaign(String campaignId) {
        LambdaQueryWrapper<AdCreative> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdCreative::getCampaignId, campaignId);
        wrapper.orderByDesc(AdCreative::getId);
        return adCreativeMapper.selectList(wrapper);
    }

    @Override
    public AdCreative review(Long id, String status) {
        AdCreative creative = adCreativeMapper.selectById(id);
        if (creative == null) {
            throw new IllegalArgumentException("素材不存在：id=" + id);
        }
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("审核状态仅支持 APPROVED / REJECTED");
        }
        creative.setStatus(status);
        adCreativeMapper.updateById(creative);
        return creative;
    }
}
