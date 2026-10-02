package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;
import com.amz.model.AmzProduct;
import com.amz.result.Result;
import com.amz.service.ProductMasterService;
import lombok.extern.slf4j.Slf4j;
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
 * 商品主数据端点。
 * <p>
 * 2026-10-02 功能覆盖清点新增：{@code AmzProduct}（含 brand / sizeTier / weightG / marketplaceId）
 * 此前只有 ListingCopyService 在读，没有任何写入与查询入口，导致 FBA 费用试算只能靠临时传参、
 * 跨店铺的同一 ASIN 映射无从查起。旧的 {@code /product/getProductList} 一族走
 * {@code @Deprecated} 的 12 字段实体，保持不动，新页面走本控制器。
 */
@Slf4j
@RestController
@RequestMapping("/product/master")
public class ProductMasterController {

    @Autowired
    private ProductMasterService productMasterService;

    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<AmzProduct>> list(@PathVariable Long shopId,
                                         @RequestParam(required = false) String asin,
                                         @RequestParam(required = false) String keyword) {
        try {
            return Result.success(productMasterService.list(shopId, asin, keyword));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @ShopScoped
    @GetMapping("/{shopId}/{id}")
    public Result<AmzProduct> get(@PathVariable Long shopId, @PathVariable Long id) {
        try {
            return Result.success(productMasterService.get(shopId, id));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @ShopScoped
    @PostMapping
    public Result<AmzProduct> create(@RequestBody AmzProduct product) {
        try {
            return Result.success(productMasterService.create(product));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @ShopScoped
    @PutMapping("/{shopId}/{id}")
    public Result<AmzProduct> update(@PathVariable Long shopId,
                                     @PathVariable Long id,
                                     @RequestBody AmzProduct patch) {
        try {
            return Result.success(productMasterService.update(shopId, id, patch));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 同一 ASIN 在"我管的店铺"里的登记情况。店铺集合取登录态里的授权列表，
     * 不接受调用方传 shopIds，否则就变成越权查询入口。
     */
    @GetMapping("/by-asin")
    public Result<List<AmzProduct>> byAsin(@RequestParam String asin) {
        List<Long> shops = UserContext.getShops();
        if (shops == null || shops.isEmpty()) {
            return Result.failure("当前账号没有可访问的店铺");
        }
        try {
            return Result.success(productMasterService.listByAsinAcrossShops(shops, asin));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }
}
