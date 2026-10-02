package com.amz.controller;
import com.amz.annotation.ShopScoped;

import com.amz.mapper.ProfitReportMapper;
import com.amz.model.ProfitReport;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 利润报告查询接口。
 * <p>
 * 类级映射使用 {@code /order/profit} 而非 {@code /profit}：网关仅配置了
 * {@code Path=/order/**} 路由到本服务，裸 {@code /profit} 前缀在网关侧无匹配路由，
 * 外部调用恒 404（前端 {@code /order/profit/summary/{shopId}} 即因此断链）。
 */
@RestController
@RequestMapping("/order/profit")
public class ProfitController {

    /**
     * 月度汇总一次最多返回多少行（SKU × 月）。页面上这是一张不分页的汇总表，
     * 所以行数必须由后端封住：它随「月份数 × SKU 数」线性增长，没有上限时
     * 跑得越久查得越贵，而且调用方无法区分「就这些」和「被截断了」。
     */
    static final int MAX_SUMMARY_ROWS = 500;

    @Autowired
    private ProfitReportMapper profitReportMapper;

    /**
     * 查询某订单利润（keyset 分页，按记录 id 倒序）。
     *
     * @param size   页大小，缺省 {@link PageRequest#DEFAULT_SIZE}
     * @param cursor 上一页返回的游标；非法游标按参数错误处理，不会退化成「从头再读一遍」
     */
    @ShopScoped
    @GetMapping("/order/{shopId}/{amazonOrderId}")
    public Result<List<ProfitReport>> getByOrder(@PathVariable Long shopId,
                                                 @PathVariable String amazonOrderId,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String cursor) {
        return Result.paged(pageByShopAnd(shopId, req -> req
                .eq(ProfitReport::getAmazonOrderId, amazonOrderId), size, cursor));
    }

    /**
     * 查询某 SKU 所有利润记录（keyset 分页，按记录 id 倒序）。
     */
    @ShopScoped
    @GetMapping("/sku/{shopId}/{sku}")
    public Result<List<ProfitReport>> getBySku(@PathVariable Long shopId,
                                               @PathVariable String sku,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String cursor) {
        return Result.paged(pageByShopAnd(shopId, req -> req.eq(ProfitReport::getSku, sku), size, cursor));
    }

    /**
     * 月度汇总（按 SKU × 月分组，最近月份在前，行数上限 {@value #MAX_SUMMARY_ROWS}）。
     */
    @ShopScoped
    @GetMapping("/summary/{shopId}")
    public Result<List<Map<String, Object>>> summary(@PathVariable Long shopId) {
        return Result.success(profitReportMapper.selectMonthlySummary(shopId, MAX_SUMMARY_ROWS));
    }

    /**
     * 三条下钻里两条明细查询共用的分页形状：店铺条件恒定，附加条件由调用方给，
     * 多探测一行才知道有没有下一页。
     */
    private PageResult<ProfitReport> pageByShopAnd(Long shopId,
                                                  java.util.function.UnaryOperator<LambdaQueryWrapper<ProfitReport>> extra,
                                                  Integer size,
                                                  String cursor) {
        PageRequest page = PageRequest.of(size, cursor);
        Long cursorId = page.cursorId();
        LambdaQueryWrapper<ProfitReport> wrapper = extra.apply(
                new LambdaQueryWrapper<ProfitReport>().eq(ProfitReport::getShopId, shopId));
        if (cursorId != null) {
            wrapper.lt(ProfitReport::getId, cursorId);
        }
        wrapper.orderByDesc(ProfitReport::getId).last("LIMIT " + page.probeSize());
        List<ProfitReport> rows = profitReportMapper.selectList(wrapper);
        return PageResult.of(rows, page.size(), r -> PageRequest.encodeCursor(r.getId()));
    }
}
