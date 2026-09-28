package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.AdCampaignExt;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.AdCampaignExtService;
import com.amz.service.AdReportExtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * POST /ad/campaigns?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping
    public Result<AdCampaignExt> create(@RequestParam Long shopId,
                                        @RequestBody AdCampaignExt campaign) {
        return Result.success(campaignExtService.createCampaign(shopId, campaign));
    }

    /**
     * 更新广告活动。
     * PUT /ad/campaigns?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PutMapping
    public Result<AdCampaignExt> update(@RequestParam Long shopId,
                                        @RequestBody AdCampaignExt campaign) {
        return Result.success(campaignExtService.updateCampaign(shopId, campaign));
    }

    /**
     * 查询店铺广告活动列表（按 adType 筛选）。
     * GET /ad/campaigns/list/{shopId}?adType=SP
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<AdCampaignExt>> list(@PathVariable Long shopId,
                                            @RequestParam(required = false) String adType,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String cursor) {
        return Result.paged(campaignExtService.listCampaigns(shopId, adType, PageRequest.of(size, cursor)));
    }

    /**
     * 批量创建广告活动。
     * POST /ad/campaigns/batch?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/batch")
    public Result<List<AdCampaignExt>> batchCreate(@RequestParam Long shopId,
                                                   @RequestBody List<AdCampaignExt> campaigns) {
        return Result.success(campaignExtService.batchCreate(shopId, campaigns));
    }

    /**
     * 批量更新状态。
     * PUT /ad/campaigns/batch/status?shopId=1&ids=1,2,3&status=PAUSED
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PutMapping("/batch/status")
    public Result<List<AdCampaignExt>> batchUpdateStatus(@RequestParam Long shopId,
                                                         @RequestParam List<Long> ids,
                                                         @RequestParam String status) {
        return Result.success(campaignExtService.batchUpdateStatus(shopId, ids, status));
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
