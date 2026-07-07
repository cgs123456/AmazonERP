package com.amz.controller;

import com.amz.mapper.ListingCopyTaskMapper;
import com.amz.model.ListingCopyTask;
import com.amz.result.Result;
import com.amz.service.ListingCopyService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跨站点 Listing 复制接口。
 */
@RestController
@RequestMapping("/listing")
public class ListingController {

    @Autowired
    private ListingCopyService listingCopyService;

    @Autowired
    private ListingCopyTaskMapper listingCopyTaskMapper;

    /**
     * 创建跨站点复制任务。
     */
    @PostMapping("/copy")
    public Result<ListingCopyTask> copy(@RequestBody CopyRequest req) {
        if (req.getShopId() == null) {
            return Result.failure("shopId 不能为空");
        }
        if (req.getSku() == null || req.getSku().trim().isEmpty()) {
            return Result.failure("sku 不能为空");
        }
        if (req.getSourceMarketplaceId() == null || req.getTargetMarketplaceId() == null) {
            return Result.failure("源/目标 marketplaceId 不能为空");
        }
        try {
            ListingCopyTask task = listingCopyService.createCopyTask(
                    req.getShopId(),
                    req.getSku(),
                    req.getSourceMarketplaceId(),
                    req.getTargetMarketplaceId(),
                    req.getTargetLanguage(),
                    req.getPriceMarkup());
            return Result.success(task);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 查询单个任务状态。
     */
    @GetMapping("/task/{taskId}")
    public Result<ListingCopyTask> getTask(@PathVariable Long taskId) {
        ListingCopyTask task = listingCopyTaskMapper.selectById(taskId);
        if (task == null) {
            return Result.failure("任务不存在");
        }
        return Result.success(task);
    }

    /**
     * 列出店铺所有复制任务。
     */
    @GetMapping("/tasks/{shopId}")
    public Result<List<ListingCopyTask>> listTasks(@PathVariable Long shopId) {
        QueryWrapper<ListingCopyTask> qw = new QueryWrapper<>();
        qw.eq("shop_id", shopId).orderByDesc("create_time");
        return Result.success(listingCopyTaskMapper.selectList(qw));
    }

    /**
     * 复制请求体。
     */
    public static class CopyRequest {
        private Long shopId;
        private String sku;
        private String sourceMarketplaceId;
        private String targetMarketplaceId;
        private String targetLanguage;
        private BigDecimal priceMarkup;

        public Long getShopId() { return shopId; }
        public void setShopId(Long shopId) { this.shopId = shopId; }
        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public String getSourceMarketplaceId() { return sourceMarketplaceId; }
        public void setSourceMarketplaceId(String sourceMarketplaceId) { this.sourceMarketplaceId = sourceMarketplaceId; }
        public String getTargetMarketplaceId() { return targetMarketplaceId; }
        public void setTargetMarketplaceId(String targetMarketplaceId) { this.targetMarketplaceId = targetMarketplaceId; }
        public String getTargetLanguage() { return targetLanguage; }
        public void setTargetLanguage(String targetLanguage) { this.targetLanguage = targetLanguage; }
        public BigDecimal getPriceMarkup() { return priceMarkup; }
        public void setPriceMarkup(BigDecimal priceMarkup) { this.priceMarkup = priceMarkup; }
    }
}
