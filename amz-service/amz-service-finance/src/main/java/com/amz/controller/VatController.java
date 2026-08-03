package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.result.Result;
import com.amz.service.VatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/finance/vat")
public class VatController {

    @Autowired
    private VatService vatService;

    @GetMapping("/calculate")
    @ShopScoped
    public Result<String> calculateVat(@RequestParam BigDecimal amount, @RequestParam String country) {
        BigDecimal vat = vatService.calculateVat(amount, country);
        return Result.success("VAT: " + vat + " (rate: " + vatService.getVatRate(country) + ")");
    }

    @GetMapping("/rate/{country}")
    @ShopScoped
    public Result<String> getRate(@PathVariable String country) {
        return Result.success(vatService.getVatRate(country).toString());
    }

    @GetMapping("/threshold-check")
    @ShopScoped
    public Result<String> checkThreshold(@RequestParam String country, @RequestParam BigDecimal annualSales) {
        boolean exceeded = vatService.exceedsThreshold(country, annualSales);
        return Result.success(exceeded ? "EXCEEDED" : "WITHIN_THRESHOLD");
    }

    @PostMapping("/monthly-close/{shopId}/{year}/{month}")
    @ShopScoped
    public Result<Object> monthlyClosing(@PathVariable Long shopId, @PathVariable int year, @PathVariable int month) {
        return Result.success(vatService.monthlyClosing(shopId, year, month));
    }
}
