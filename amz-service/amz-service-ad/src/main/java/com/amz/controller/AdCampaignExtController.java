package com.amz.controller;
import com.amz.annotation.ShopScoped;

import com.amz.model.AdCampaignExt;
import com.amz.result.Result;
import com.amz.service.AdCampaignExtService;
import com.amz.service.AdReportExtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 广告活动扩展 REST 端点（支持 SP/SB/SD/DSP 全广告类型）。
 */
@RestController
@RequestMapping("/ad/campaigns")
public class AdCampaignExtController {

    @Autowired
    private AdCampaignExtService campaignExtService;

    @Autowired
    private AdReportExtService reportExtService;

    /**
     * 创建广告活动。
     * POST /ad/campaigns
     */
    @PostMapping
    public Result<AdCampaignExt> create(@RequestBody AdCampaignExt campaign) {
        return Result.success(campaignExtService.createCampaign(campaign));
    }

    /**
     * 更新广告活动。
     * PUT /ad/campaigns
     */
    @PutMapping
    public Result<AdCampaignExt> update(@RequestBody AdCampaignExt campaign) {
        return Result.success(campaignExtService.updateCampaign(campaign));
    }

    /**
     * 查询店铺广告活动列表（按 adType 筛选）。
     * GET /ad/campaigns/list/{shopId}?adType=SP
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<AdCampaignExt>> list(@PathVariable Long shopId,
                                            @RequestParam(required = false) String adType) {
        return Result.success(campaignExtService.listCampaigns(shopId, adType));
    }

    /**
     * 批量创建广告活动。
     * POST /ad/campaigns/batch
     */
    @PostMapping("/batch")
    public Result<List<AdCampaignExt>> batchCreate(@RequestBody List<AdCampaignExt> campaigns) {
        return Result.success(campaignExtService.batchCreate(campaigns));
    }

    /**
     * 批量更新状态。
     * PUT /ad/campaigns/batch/status?ids=1,2,3&status=PAUSED
     */
    @PutMapping("/batch/status")
    public Result<List<AdCampaignExt>> batchUpdateStatus(@RequestParam List<Long> ids,
                                                         @RequestParam String status) {
        return Result.success(campaignExtService.batchUpdateStatus(ids, status));
    }

    /**
     * 综合报表：按广告类型汇总。
     * GET /ad/campaigns/summary/type/{shopId}
     */
    @ShopScoped
    @GetMapping("/summary/type/{shopId}")
    public Result<Map<String, Map<String, Object>>> summaryByType(@PathVariable Long shopId) {
        return Result.success(reportExtService.getSummaryByType(shopId));
    }

    /**
     * 综合报表：店铺整体汇总。
     * GET /ad/campaigns/summary/{shopId}
     */
    @ShopScoped
    @GetMapping("/summary/{shopId}")
    public Result<Map<String, Object>> summary(@PathVariable Long shopId) {
        return Result.success(reportExtService.getShopSummary(shopId));
    }
}
