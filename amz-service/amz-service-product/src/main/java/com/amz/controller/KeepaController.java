package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.client.KeepaClient;
import com.amz.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product/keepa")
public class KeepaController {

    @Autowired
    private KeepaClient keepaClient;

    @GetMapping("/price/{asin}")
    @ShopScoped
    public Result<String> priceHistory(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        return Result.success(keepaClient.getPriceHistory(asin, domain));
    }

    @GetMapping("/rank/{asin}")
    @ShopScoped
    public Result<String> rankHistory(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        return Result.success(keepaClient.getRankHistory(asin, domain));
    }

    @GetMapping("/competitor/{asin}")
    @ShopScoped
    public Result<String> competitorAnalysis(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        return Result.success(keepaClient.getCompetitorAnalysis(asin, domain));
    }
}
