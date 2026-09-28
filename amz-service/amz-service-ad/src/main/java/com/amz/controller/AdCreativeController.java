package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.AdCreative;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.AdCreativeService;
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
     * POST /ad/creatives?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping
    public Result<AdCreative> create(@RequestParam Long shopId,
                                     @RequestBody AdCreative creative) {
        return Result.success(adCreativeService.createCreative(shopId, creative));
    }

    /**
     * 更新广告素材。
     * PUT /ad/creatives?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PutMapping
    public Result<AdCreative> update(@RequestParam Long shopId,
                                     @RequestBody AdCreative creative) {
        return Result.success(adCreativeService.updateCreative(shopId, creative));
    }

    /**
     * 查询活动的素材列表。
     * GET /ad/creatives/list/{campaignId}?shopId=1
     */
    @ShopScoped
    @GetMapping("/list/{campaignId}")
    public Result<List<AdCreative>> list(@RequestParam Long shopId,
                                         @PathVariable String campaignId,
                                         @RequestParam(required = false) Integer size,
                                         @RequestParam(required = false) String cursor) {
        return Result.paged(adCreativeService.listByCampaign(
                shopId, campaignId, PageRequest.of(size, cursor)));
    }

    /**
     * 素材审核：PENDING → APPROVED / REJECTED。
     * PUT /ad/creatives/{id}/review?shopId=1&status=APPROVED
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PutMapping("/{id}/review")
    public Result<AdCreative> review(@RequestParam Long shopId,
                                     @PathVariable Long id,
                                     @RequestParam String status) {
        return Result.success(adCreativeService.review(shopId, id, status));
    }
}
