package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.client.KeepaClient;
import com.amz.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/product/keepa")
public class KeepaController {

    @Autowired
    private KeepaClient keepaClient;

    @GetMapping("/price/{asin}")
    @ShopScoped
    public Result<String> priceHistory(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        String data = keepaClient.getPriceHistory(asin, domain);
        if (data == null) return Result.failure("Keepa data unavailable");
        return Result.success(data);
    }

    @GetMapping("/rank/{asin}")
    @ShopScoped
    public Result<String> rankHistory(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        String data = keepaClient.getRankHistory(asin, domain);
        if (data == null) return Result.failure("Keepa data unavailable");
        return Result.success(data);
    }

    @GetMapping("/competitor/{asin}")
    @ShopScoped
    public Result<String> competitorAnalysis(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        String data = keepaClient.getCompetitorAnalysis(asin, domain);
        if (data == null) return Result.failure("Keepa data unavailable");
        return Result.success(data);
    }
}
