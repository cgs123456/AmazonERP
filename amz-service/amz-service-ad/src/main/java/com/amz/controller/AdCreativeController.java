package com.amz.controller;

import com.amz.model.AdCreative;
import com.amz.result.Result;
import com.amz.service.AdCreativeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SB 广告素材管理 REST 端点。
 */
@RestController
@RequestMapping("/ad/creatives")
public class AdCreativeController {

    @Autowired
    private AdCreativeService adCreativeService;

    /**
     * 创建广告素材。
     * POST /ad/creatives
     */
    @PostMapping
    public Result<AdCreative> create(@RequestBody AdCreative creative) {
        return Result.success(adCreativeService.createCreative(creative));
    }

    /**
     * 更新广告素材。
     * PUT /ad/creatives
     */
    @PutMapping
    public Result<AdCreative> update(@RequestBody AdCreative creative) {
        return Result.success(adCreativeService.updateCreative(creative));
    }

    /**
     * 查询活动的素材列表。
     * GET /ad/creatives/list/{campaignId}
     */
    @GetMapping("/list/{campaignId}")
    public Result<List<AdCreative>> list(@PathVariable String campaignId) {
        return Result.success(adCreativeService.listByCampaign(campaignId));
    }

    /**
     * 素材审核：PENDING → APPROVED / REJECTED。
     * PUT /ad/creatives/{id}/review?status=APPROVED
     */
    @PutMapping("/{id}/review")
    public Result<AdCreative> review(@PathVariable Long id, @RequestParam String status) {
        return Result.success(adCreativeService.review(id, status));
    }
}
