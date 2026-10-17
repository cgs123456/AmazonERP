package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.result.Result;
import com.amz.service.VatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * VAT 计算与月结。
 *
 * <p><b>为什么三个计算端点没有 {@code @ShopScoped}</b>（§S33.45）：{@code calculate} /
 * {@code rate} / {@code threshold-check} 的入参只有 {@code amount} + {@code country}
 * （或 {@code annualSales} + {@code country}），返回的是税率与税额——**无状态计算器，
 * 不触任何店铺数据**，方法签名里没有店铺维度可供切面解析，
 * {@code ShopIdGuardAspect} 会走「未找到 shopId 参数，跳过」直接放行。
 * 这枚注解在这里是装饰（与 §S33.41 的 {@code PerformanceController.detail} 同类），
 * 按「删除优于兼容」移除。
 *
 * <p>真正接触店铺数据的是 {@link #monthlyClosing}，它有 shopId 路径参数、
 * 有 {@code @ShopScoped}、有 {@code @RequireRole}，与这三个不是一类。
 */
@RestController
@RequestMapping("/finance/vat")
public class VatController {

    @Autowired
    private VatService vatService;

    @GetMapping("/calculate")
    public Result<String> calculateVat(@RequestParam BigDecimal amount, @RequestParam String country) {
        BigDecimal vat = vatService.calculateVat(amount, country);
        return Result.success("VAT: " + vat + " (rate: " + vatService.getVatRate(country) + ")");
    }

    @GetMapping("/rate/{country}")
    public Result<String> getRate(@PathVariable String country) {
        return Result.success(vatService.getVatRate(country).toString());
    }

    @GetMapping("/threshold-check")
    public Result<String> checkThreshold(@RequestParam String country, @RequestParam BigDecimal annualSales) {
        boolean exceeded = vatService.exceedsThreshold(country, annualSales);
        return Result.success(exceeded ? "EXCEEDED" : "WITHIN_THRESHOLD");
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "VAT 月结", action = "VAT 月", description = "VAT 月（需追责）")
    @PostMapping("/monthly-close/{shopId}/{year}/{month}")
    @ShopScoped
    public Result<Object> monthlyClosing(@PathVariable Long shopId, @PathVariable int year, @PathVariable int month) {
        return Result.success(vatService.monthlyClosing(shopId, year, month));
    }
}
