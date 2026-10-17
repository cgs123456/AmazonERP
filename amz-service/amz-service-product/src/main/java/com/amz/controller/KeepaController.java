package com.amz.controller;

import com.amz.client.KeepaClient;
import com.amz.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keepa 第三方数据查询（原始透传）。
 *
 * <p><b>为什么这里没有 {@code @ShopScoped}</b>（§S33.41）：三个端点的参数是
 * {@code asin} + {@code domain}，后者是 Keepa 自己的站点编码（1=US、3=UK…），
 * 不是本仓的 {@code shopId}——方法签名里没有任何店铺维度可供切面解析，
 * {@code ShopIdGuardAspect} 会走「未找到 shopId 参数，跳过」分支直接放行。
 * 这枚注解在这里**看着在、实际不设防**，与 {@code PerformanceController.detail}
 * （§S33.41 已修）和 {@code AdServiceImpl.createBidSchedule}（§S33.36 已删）
 * 是同一类问题。
 *
 * <p>按「删除优于兼容」移除：留一枚无效注解比没有注解更糟，下一位会以为
 * 这里已经有租户防护。
 *
 * <p><b>但店铺隔离的缺口仍在，这是已知边界</b>：Keepa 按 ASIN 返回的是公开市场
 * 数据（价格/排名/竞品），不含店铺自有信息，越权收益有限。店铺级竞品数据走
 * 另一条链路——{@code KeepaCompetitorScheduler} 拉取后落
 * {@code amz_competitor_monitor}，由 {@code ListingMonitorController} 按 shopId
 * 严格校验后暴露。前端应消费后者，不是这里的裸透传。
 *
 * <p><b>移除/补齐条件</b>：Keepa 接入需要按店铺计费或限流时，给这三个端点加
 * shopId 参数并补回 {@code @ShopScoped}；或确认无人调用后整类删除
 * （HANDOFF §S33.39：这三个端点目前零调用方，属冗余透传）。
 */
@RestController
@RequestMapping("/product/keepa")
public class KeepaController {

    @Autowired
    private KeepaClient keepaClient;

    @GetMapping("/price/{asin}")
    public Result<String> priceHistory(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        return Result.success(keepaClient.getPriceHistory(asin, domain));
    }

    @GetMapping("/rank/{asin}")
    public Result<String> rankHistory(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        return Result.success(keepaClient.getRankHistory(asin, domain));
    }

    @GetMapping("/competitor/{asin}")
    public Result<String> competitorAnalysis(@PathVariable String asin, @RequestParam(defaultValue = "1") int domain) {
        return Result.success(keepaClient.getCompetitorAnalysis(asin, domain));
    }
}
