package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredentialAdminService;
import com.amz.credential.ShopCredentialConcurrentUpdateException;
import com.amz.credential.ShopCredentialStatus;
import com.amz.credential.ShopCredentialUpdateRequest;
import com.amz.result.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺 SP-API 凭证管理端点。
 *
 * <p>凭证只写不回显。所有端点要求 ADMIN，并在服务调用前完成显式店铺授权校验，
 * 避免直接调用场景绕过切面或依赖请求体中的 shopId。</p>
 */
@RestController
@RequestMapping("/spapi/credentials")
public class ShopCredentialController {

    private final ShopCredentialAdminService service;

    public ShopCredentialController(ShopCredentialAdminService service) {
        this.service = service;
    }

    @GetMapping("/shop/{shopId}/status")
    @RequireRole({"ADMIN"})
    public Result<ShopCredentialStatus> status(@PathVariable("shopId") Long shopId) {
        if (!canManageCredentials(shopId)) {
            return forbidden();
        }
        return Result.success(service.status(shopId));
    }

    @PutMapping("/shop/{shopId}")
    @RequireRole({"ADMIN"})
    public Result<ShopCredentialStatus> upsert(@PathVariable("shopId") Long shopId,
                                                @RequestBody ShopCredentialUpdateRequest request) {
        if (!canManageCredentials(shopId)) {
            return forbidden();
        }
        try {
            return Result.success(service.upsert(shopId, request));
        } catch (ShopCredentialConcurrentUpdateException e) {
            return Result.failure(e.getMessage(),
                    ErrorSummary.localError(LocalApiException.CODE_CONFLICT));
        } catch (IllegalArgumentException e) {
            return invalid(e);
        }
    }

    @DeleteMapping("/shop/{shopId}")
    @RequireRole({"ADMIN"})
    public Result<ShopCredentialStatus> delete(@PathVariable("shopId") Long shopId) {
        if (!canManageCredentials(shopId)) {
            return forbidden();
        }
        return Result.success(service.delete(shopId));
    }

    private static boolean canManageCredentials(Long shopId) {
        return "ADMIN".equalsIgnoreCase(UserContext.getRole())
                && UserContext.isShopAllowedStrict(shopId);
    }

    private static <T> Result<T> forbidden() {
        return Result.failure("无权限管理该店铺凭证",
                ErrorSummary.localError(LocalApiException.CODE_FORBIDDEN));
    }

    private static <T> Result<T> invalid(IllegalArgumentException error) {
        return Result.failure(error.getMessage(),
                ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
    }
}
