package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.Bom;
import com.amz.result.Result;
import com.amz.service.impl.BomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 物料清单（BOM，组合装）接口（P2.4）。
 * <p>
 * 组合装履约前按 BOM 展开成子品需求再扣减——本地仓按子品备货，
 * 组合 SKU 自身没有库存行。展开逻辑（含环检测）见 {@link BomService#expand}。
 */
@Slf4j
@RestController
@RequestMapping("/procurement/bom")
public class BomController {

    @Autowired
    private BomService bomService;

    /**
     * 新增/更新一条 BOM 关系（同组合+同子品幂等覆盖）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "BOM", action = "维护", description = "新增或更新组合装 BOM 关系")
    @PostMapping
    public Result<Bom> save(@RequestBody Bom bom) {
        try {
            return Result.success(bomService.save(bom));
        } catch (Exception e) {
            log.warn("保存 BOM 失败: {}", e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 删除一条 BOM 关系。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "BOM", action = "删除", description = "删除 BOM 关系")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id, @RequestParam Long shopId) {
        try {
            bomService.delete(shopId, id);
            return Result.success(true);
        } catch (Exception e) {
            log.warn("删除 BOM 失败 id={}: {}", id, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 某组合 SKU 的直接子品。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<Bom>> listByComposite(@PathVariable Long shopId,
                                             @RequestParam String compositeSku) {
        try {
            return Result.success(bomService.listByComposite(shopId, compositeSku));
        } catch (Exception e) {
            log.warn("查询 BOM 失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 递归展开需求（order 履约编排消费）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/expand/{shopId}")
    public Result<List<BomService.BomLine>> expand(@PathVariable Long shopId,
                                                   @RequestBody List<BomService.BomLine> requests) {
        try {
            return Result.success(bomService.expand(shopId, requests));
        } catch (Exception e) {
            log.warn("BOM 展开失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }
}
