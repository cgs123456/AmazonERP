package com.amz.controller;

import com.amz.model.AdTargeting;
import com.amz.result.Result;
import com.amz.service.AdTargetingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
     * POST /ad/targeting
     */
    @PostMapping
    public Result<AdTargeting> create(@RequestBody AdTargeting targeting) {
        return Result.success(adTargetingService.createTargeting(targeting));
    }

    /**
     * 更新定向规则。
     * PUT /ad/targeting
     */
    @PutMapping
    public Result<AdTargeting> update(@RequestBody AdTargeting targeting) {
        return Result.success(adTargetingService.updateTargeting(targeting));
    }

    /**
     * 查询活动的定向规则列表。
     * GET /ad/targeting/list/{campaignId}?targetingType=CONTEXTUAL
     */
    @GetMapping("/list/{campaignId}")
    public Result<List<AdTargeting>> list(@PathVariable String campaignId,
                                          @RequestParam(required = false) String targetingType) {
        return Result.success(adTargetingService.listByCampaign(campaignId, targetingType));
    }

    /**
     * 删除定向规则。
     * DELETE /ad/targeting/{id}
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adTargetingService.delete(id);
        return Result.success(null);
    }
}
