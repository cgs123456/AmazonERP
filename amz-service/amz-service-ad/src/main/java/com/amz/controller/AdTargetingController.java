package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.AdTargeting;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.AdTargetingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * SD 受众定向管理 REST 端点。
 */
@RestController
@RequestMapping("/ad/targeting")
public class AdTargetingController {

    @Autowired
    private AdTargetingService adTargetingService;

    /**
     * 创建定向规则。
     * POST /ad/targeting?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping
    public Result<AdTargeting> create(@RequestParam Long shopId,
                                      @RequestBody AdTargeting targeting) {
        return Result.success(adTargetingService.createTargeting(shopId, targeting));
    }

    /**
     * 更新定向规则。
     * PUT /ad/targeting?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PutMapping
    public Result<AdTargeting> update(@RequestParam Long shopId,
                                      @RequestBody AdTargeting targeting) {
        return Result.success(adTargetingService.updateTargeting(shopId, targeting));
    }

    /**
     * 查询活动的定向规则列表。
     * GET /ad/targeting/list/{campaignId}?shopId=1&targetingType=CONTEXTUAL
     */
    @ShopScoped
    @GetMapping("/list/{campaignId}")
    public Result<List<AdTargeting>> list(@RequestParam Long shopId,
                                          @PathVariable String campaignId,
                                          @RequestParam(required = false) String targetingType,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String cursor) {
        return Result.paged(adTargetingService.listByCampaign(
                shopId, campaignId, targetingType, PageRequest.of(size, cursor)));
    }

    /**
     * 删除定向规则。
     * DELETE /ad/targeting/{id}?shopId=1
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestParam Long shopId, @PathVariable Long id) {
        adTargetingService.delete(shopId, id);
        return Result.success(null);
    }
}
