package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.model.pojo.Shop;
import com.amz.model.vo.ShopUserVo;
import com.amz.model.vo.ShopVo;
import com.amz.result.Result;
import com.amz.service.ShopService;
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
 * 店铺主数据接口（多店铺 ERP 的店铺选择器真实数据源）。
 * <p>
 * <b>存在动因</b>：此前全仓没有一个店铺端点，前端 {@code main.ts} 只能写死
 * 「Shop A (US) / Shop B (UK) / Shop C (DE)」。这三个假店铺会被写进 localStorage，
 * 并被 axios 拦截器当作 {@code shopId} 请求头发给网关——也就是说，店铺隔离这条
 * 最重要的安全边界，前端一直在用编造的 ID 去撞。本 Controller 补上真实来源。
 * <p>
 * <b>权限</b>：列表对任何登录用户开放（按归属过滤）；写操作全部 {@code @RequireRole ADMIN}。
 * 这里不用 {@code @ShopScoped}：店铺是租户之上的概念，店铺管理接口拿不到
 * 单一 shopId 上下文，归属判定落在服务层。
 * <p>
 * <b>已知限制</b>：JWT 的 shops claim 有效期 24 小时，分配/解绑后受影响用户需重新登录
 * 才能看到新店铺。这是刻意取舍（鉴权路径不查库），前端会在操作完成后提示。
 */
@Slf4j
@RestController
@RequestMapping("/user/shop")
public class ShopController {

    @Autowired
    private ShopService shopService;

    /**
     * 我的店铺列表。
     */
    @GetMapping("/list")
    public Result<List<ShopVo>> listMyShops() {
        try {
            return Result.success(shopService.listMyShops());
        } catch (Exception e) {
            log.error("查询店铺列表失败", e);
            return Result.failure("查询店铺列表失败：" + e.getMessage());
        }
    }

    /**
     * 创建店铺（ADMIN）。请求体不得携带凭证字段。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "店铺", action = "新增", description = "创建店铺主数据")
    @PostMapping
    public Result<Shop> createShop(@RequestBody Shop shop) {
        try {
            return Result.success(shopService.createShop(shop));
        } catch (Exception e) {
            log.warn("创建店铺失败: {}", e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 更新店铺主数据（ADMIN）。null 字段表示不修改。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "店铺", action = "修改", description = "更新店铺主数据")
    @PutMapping("/{shopId}")
    public Result<Shop> updateShop(@PathVariable Long shopId, @RequestBody Shop patch) {
        try {
            return Result.success(shopService.updateShop(shopId, patch));
        } catch (Exception e) {
            log.warn("更新店铺失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 更新店铺授权状态（ADMIN）。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "店铺", action = "状态变更", description = "更新店铺授权状态")
    @PostMapping("/{shopId}/status")
    public Result<Shop> updateStatus(@PathVariable Long shopId, @RequestParam Integer status) {
        try {
            return Result.success(shopService.updateShopStatus(shopId, status));
        } catch (Exception e) {
            log.warn("更新店铺状态失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 某店铺已绑定的用户列表（ADMIN）。授权界面的「当前绑定」读侧——
     * 没有它，分配/解绑就是盲写（看不到现状、解绑只能靠报错试）。
     */
    @RequireRole({"ADMIN"})
    @GetMapping("/{shopId}/users")
    public Result<List<ShopUserVo>> listShopUsers(@PathVariable Long shopId) {
        try {
            return Result.success(shopService.listShopUsers(shopId));
        } catch (Exception e) {
            log.warn("查询店铺绑定用户失败 shopId={}: {}", shopId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 把用户分配到店铺（ADMIN）。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "店铺", action = "授权", description = "分配用户到店铺并指定店铺级角色")
    @PostMapping("/{shopId}/assign")
    public Result<Boolean> assignUser(@PathVariable Long shopId,
                                      @RequestParam Long userId,
                                      @RequestParam(required = false) String role) {
        try {
            shopService.assignUser(shopId, userId, role);
            return Result.success(true);
        } catch (Exception e) {
            log.warn("分配用户到店铺失败 shopId={} userId={}: {}", shopId, userId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 解除用户-店铺绑定（ADMIN）。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "店铺", action = "回收", description = "解除用户与店铺的绑定")
    @PostMapping("/{shopId}/assign/remove")
    public Result<Boolean> unassignUser(@PathVariable Long shopId, @RequestParam Long userId) {
        try {
            shopService.unassignUser(shopId, userId);
            return Result.success(true);
        } catch (Exception e) {
            log.warn("解除用户-店铺绑定失败 shopId={} userId={}: {}", shopId, userId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }
}
