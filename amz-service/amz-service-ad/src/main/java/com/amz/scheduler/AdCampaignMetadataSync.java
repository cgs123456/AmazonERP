package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.model.AdCampaign;
import com.amz.model.AdCampaignExt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告活动元数据的同步与查询（名称/类型/预算/竞价策略/状态 + campaignId → adType 映射）。
 * <p>
 * 从 {@link AdReportSyncScheduler} 拆出（§S33.83）：元数据是「从 Ads API 拉活动列表并
 * 落本地配置表」这一独立职责，与「日报拉取与幂等 upsert」不同生命周期——
 * 元数据失败不阻断日报同步，两者只通过「本次是否有 metadataWarning」与
 * adType 映射两个结果交互。拆出后 scheduler 只管日报口径。
 * <p>
 * 本类是 ad 模块内唯一的元数据写入方；日报同步依赖它提供的 adType 映射，
 * 映射缺失时 adType 留空（不猜 SP/SB/SD）。
 */
@Slf4j
@Component
public class AdCampaignMetadataSync {

    /** 单次 keyset 查询的活动页大小上限，防止配置错误把整表重新拉回内存。 */
    private static final int MAX_PAGE_SIZE = 1_000;

    @Value("${advertising.scheduler.campaign-page-size:500}")
    private int campaignPageSize = 500;

    @Autowired
    private AdCampaignExtMapper adCampaignExtMapper;

    @Autowired
    private AdvertisingApiClient advertisingApiClient;

    /**
     * 从 Advertising API 刷新活动元数据。
     * <p>
     * 元数据失败不阻断日报同步：报表接口可能已授权，而活动列表接口暂不可用；
     * 错误会记录日志，调用方可从同步统计识别。活动本身不返回性能字段，
     * 因此 upsert 只覆盖名称、类型、预算、竞价策略和状态。
     *
     * @return true 表示本次同步有失败（供调用方计入 metadataWarnings）
     */
    public boolean syncMetadata(Long shopId) {
        List<AdCampaign> campaigns;
        try {
            campaigns = advertisingApiClient.listCampaigns(shopId);
        } catch (Exception e) {
            log.error("广告活动元数据同步失败 shopId={}", shopId, e);
            return true;
        }
        if (campaigns == null || campaigns.isEmpty()) {
            return false;
        }
        boolean failed = false;
        for (AdCampaign campaign : campaigns) {
            if (campaign == null || !hasText(campaign.getCampaignId())) {
                continue;
            }
            String campaignId = campaign.getCampaignId();
            String adType = hasText(campaign.getCampaignType()) ? campaign.getCampaignType() : "SP";
            AdCampaignExt row = new AdCampaignExt();
            row.setShopId(shopId);
            row.setCampaignId(campaignId);
            row.setCampaignName(hasText(campaign.getName()) ? campaign.getName() : campaignId);
            row.setAdType(adType);
            row.setCampaignType(adType);
            row.setBudget(campaign.getDailyBudget());
            row.setBudgetType("DAILY");
            row.setBiddingStrategy(campaign.getBiddingStrategy());
            row.setStatus(hasText(campaign.getState()) ? campaign.getState() : "UNKNOWN");
            try {
                adCampaignExtMapper.upsertMetadata(row);
            } catch (Exception e) {
                log.error("广告活动元数据落库失败 shopId={} campaignId={}", shopId, campaignId, e);
                failed = true;
            }
        }
        return failed;
    }

    /**
     * 本店铺 campaignId → adType 映射（活动配置表自包含，无跨服务调用）。
     * 加载失败返回已装入的部分（可能为空），调用方按「adType 留空」处理而非中断。
     */
    public Map<String, String> loadAdTypeMap(Long shopId) {
        Map<String, String> map = new HashMap<>();
        int limit = Math.max(1, Math.min(campaignPageSize, MAX_PAGE_SIZE));
        long cursor = Long.MIN_VALUE;
        try {
            while (true) {
                List<AdCampaignExt> campaigns = adCampaignExtMapper.selectAdTypePage(shopId, cursor, limit);
                if (campaigns == null || campaigns.isEmpty()) {
                    break;
                }
                long nextCursor = cursor;
                for (AdCampaignExt c : campaigns) {
                    if (c == null) {
                        continue;
                    }
                    if (c.getId() != null && c.getId() > nextCursor) {
                        nextCursor = c.getId();
                    }
                    if (c.getCampaignId() != null && c.getAdType() != null) {
                        map.put(c.getCampaignId(), c.getAdType());
                    }
                }
                if (campaigns.size() < limit) {
                    break;
                }
                if (nextCursor <= cursor) {
                    log.error("广告活动类型分页游标未推进 shopId={} cursor={} limit={}",
                            shopId, cursor, limit);
                    break;
                }
                cursor = nextCursor;
            }
        } catch (Exception e) {
            log.error("加载活动类型映射失败 shopId={}，adType 将留空", shopId, e);
        }
        return map;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
