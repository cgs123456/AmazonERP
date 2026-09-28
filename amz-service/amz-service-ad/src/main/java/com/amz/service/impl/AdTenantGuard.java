package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.model.AdCampaignExt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 广告模块统一租户守卫。
 *
 * <p>广告素材和定向的历史表结构只有 campaign_id，没有 shop_id。仅凭 campaign_id
 * 查询无法区分不同店铺复用同一活动 ID 的情况，因此所有公开读写都必须在服务层
 * 同时校验请求 shopId 和活动归属，不能只依赖 Controller 上的 {@code @ShopScoped}。
 */
@Slf4j
@Component
public class AdTenantGuard {

    @Autowired
    private AdCampaignExtMapper campaignExtMapper;

    /**
     * 严格校验当前外部请求是否有权访问指定店铺。
     * 无用户上下文的服务间调用不能走这些公开广告接口，因此这里 fail-closed。
     */
    public void requireShopAccess(Long shopId) {
        if (shopId == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (!UserContext.isShopAllowedStrict(shopId)) {
            log.warn("广告店铺越权访问拦截：userId={}, role={}, shopId={}, authorizedShops={}",
                    UserContext.getUserId(), UserContext.getRole(), shopId, UserContext.getShops());
            throw new CodeErrorException("店铺不存在或无权访问");
        }
    }

    /**
     * 校验目标资源所属店铺必须与请求店铺一致。
     */
    public void requireResourceShop(Long requestedShopId, Long resourceShopId) {
        requireShopAccess(requestedShopId);
        if (!requestedShopId.equals(resourceShopId)) {
            log.warn("广告资源越权访问拦截：userId={}, requestedShopId={}, resourceShopId={}",
                    UserContext.getUserId(), requestedShopId, resourceShopId);
            throw new CodeErrorException("资源不存在或无权访问");
        }
    }

    /**
     * 校验活动存在且属于请求店铺。活动是素材和定向的唯一可信归属链。
     */
    public AdCampaignExt requireCampaign(Long shopId, String campaignId) {
        requireShopAccess(shopId);
        if (campaignId == null || campaignId.isBlank()) {
            throw new AttrIsNullException("活动ID不能为空");
        }
        AdCampaignExt campaign = campaignExtMapper.selectOne(
                new LambdaQueryWrapper<AdCampaignExt>()
                        .eq(AdCampaignExt::getShopId, shopId)
                        .eq(AdCampaignExt::getCampaignId, campaignId));
        if (campaign == null) {
            log.warn("广告活动归属校验失败：userId={}, shopId={}, campaignId={}",
                    UserContext.getUserId(), shopId, campaignId);
            throw new CodeErrorException("广告活动不存在或无权访问");
        }
        return campaign;
    }
}
