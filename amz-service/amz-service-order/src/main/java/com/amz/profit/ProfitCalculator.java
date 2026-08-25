package com.amz.profit;

import com.amz.client.AdServiceClient;
import com.amz.mapper.CategoryFeeRateMapper;
import com.amz.mapper.FbaFeeTableMapper;
import com.amz.mapper.OrderMapper;
import com.amz.mapper.ProductCostMapper;
import com.amz.model.CategoryFeeRate;
import com.amz.model.FbaFeeTable;
import com.amz.model.ProductCost;
import com.amz.model.ProfitReport;
import com.amz.model.pojo.Order;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 利润核算核心计算器
 * 按蓝图文档公式：
 * <pre>
 *   grossProfit = revenue - cogs - fbaFee - referralFee
 *   netProfit   = grossProfit - adCostAllocated - vat - storageFeeDaily
 *   netMargin   = netProfit / revenue
 * </pre>
 * 分摊口径（避免系统性重复扣费）：
 * <ul>
 *   <li><b>广告费</b>：店铺级广告总花费 ÷ 该店近30天订单数，摊到单订单
 *       （旧实现每单扣店铺总额，N 单即重复扣 N 倍）；</li>
 *   <li><b>仓储费</b>：FBA 月度仓储费 ÷ 30 折算日仓储（旧实现每单扣整月费用，
 *       约放大 30 倍）。</li>
 * </ul>
 */
@Slf4j
@Component
public class ProfitCalculator {

    @Autowired
    private ProductCostMapper productCostMapper;

    @Autowired
    private CategoryFeeRateMapper categoryFeeRateMapper;

    @Autowired
    private FbaFeeTableMapper fbaFeeTableMapper;

    @Autowired
    private AdServiceClient adServiceClient;

    /** 订单计数（广告费分摊分母），同模块内直接查订单表。 */
    @Autowired
    private OrderMapper orderMapper;

    /**
     * 欧盟 VAT 税率（默认 20%），可通过 amz.profit.eu-vat-rate 按环境覆盖。
     * 字段带初始值保证脱离 Spring 的单元测试可直接实例化。
     */
    @Value("${amz.profit.eu-vat-rate:0.20}")
    private BigDecimal euVatRate = new BigDecimal("0.20");

    /**
     * 广告费分摊分母（近30天订单数）的本地缓存 TTL：10 分钟。
     * 避免同一店铺的每条利润消息都触发一次 count 查询。
     */
    private static final long ORDER_COUNT_CACHE_TTL_MS = 10 * 60 * 1000L;

    private final ConcurrentHashMap<Long, OrderCountCacheEntry> orderCountCache = new ConcurrentHashMap<>();

    private static final class OrderCountCacheEntry {
        final long count;
        final long expiresAtMs;

        OrderCountCacheEntry(long count, long expiresAtMs) {
            this.count = count;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final int MONEY_SCALE = 2;
    private static final int MARGIN_SCALE = 4;

    /**
     * 月度仓储折算天数。
     */
    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);

    /**
     * 计算单订单利润
     *
     * @param shopId        店铺ID
     * @param amazonOrderId Amazon 订单号
     * @param sku           卖家SKU
     * @param revenue       收入（商品价 + 运费 + 礼品包装 - 促销折扣）
     * @param category      类目名称
     * @param sizeTier      尺寸分段（small-standard/large-standard/...）
     * @param weightG       重量（克）
     * @param region        区域（NA/EU/FE）
     * @param isEU          是否欧盟站点（影响 VAT）
     * @return 利润报告实体（未落库）
     */
    public ProfitReport calculate(Long shopId, String amazonOrderId, String sku,
                                  BigDecimal revenue, String category, String sizeTier,
                                  int weightG, String region, boolean isEU) {
        if (revenue == null) {
            revenue = BigDecimal.ZERO;
        }

        // 1. 采购成本 cogs（缺失返回 null，标记数据不完整）
        BigDecimal cogsRaw = lookupCogs(shopId, sku);
        boolean costMissing = (cogsRaw == null);
        if (costMissing) {
            log.warn("利润计算数据缺失：shopId={}, sku={}, 缺失类型=采购成本(cogs)", shopId, sku);
        }
        BigDecimal cogs = nullToZero(cogsRaw);

        // 2. FBA 履约费 + 仓储费（取最小满足 weight_g>=? 的记录）
        //    月度仓储费按天折算分摊到订单（÷30），避免整月费用重复计入单笔利润
        FbaFeeTable fbaFeeTable = lookupFbaFee(sizeTier, weightG, region);
        BigDecimal fbaFee = fbaFeeTable != null ? nullToZero(fbaFeeTable.getFulfillmentFee()) : BigDecimal.ZERO;
        BigDecimal storageFeeMonthly = fbaFeeTable != null ? nullToZero(fbaFeeTable.getStorageFeePerMonth()) : BigDecimal.ZERO;
        BigDecimal storageFee = storageFeeMonthly
                .divide(DAYS_PER_MONTH, MONEY_SCALE, RoundingMode.HALF_UP);

        // 3. 平台佣金 referralFee = revenue × referralFeeRate（缺失返回 null，标记数据不完整）
        BigDecimal referralFeeRateRaw = lookupReferralFeeRate(category);
        boolean rateMissing = (referralFeeRateRaw == null);
        if (rateMissing) {
            log.warn("利润计算数据缺失：shopId={}, sku={}, 缺失类型=类目佣金率(referralFeeRate)", shopId, sku);
        }
        BigDecimal referralFeeRate = nullToZero(referralFeeRateRaw);
        BigDecimal referralFee = revenue.multiply(referralFeeRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 4. 广告费：取店铺级广告总花费后按近30天订单数分摊到单订单，
        //    避免每条订单利润消息都扣一遍店铺总额（系统性 N 倍重复扣费）
        BigDecimal adCost = allocateAdCost(shopId, fetchAdCost(shopId));

        // 5. VAT（欧盟）：亚马逊回传收入含税，按 含税价×r/(1+r) 还原税额；
        //    税率可通过 amz.profit.eu-vat-rate 配置（默认 20%）
        BigDecimal vat = BigDecimal.ZERO;
        if (isEU && revenue.signum() > 0) {
            BigDecimal divisor = BigDecimal.ONE.add(euVatRate);
            vat = revenue.multiply(euVatRate)
                    .divide(divisor, MONEY_SCALE, RoundingMode.HALF_UP);
        }

        // 6. 毛利 = revenue - cogs - fbaFee - referralFee
        BigDecimal grossProfit = revenue
                .subtract(cogs)
                .subtract(fbaFee)
                .subtract(referralFee)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 7. 净利 = grossProfit - adCost - vat - storageFee
        BigDecimal netProfit = grossProfit
                .subtract(adCost)
                .subtract(vat)
                .subtract(storageFee)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 8. 净利率 = netProfit / revenue
        BigDecimal netMargin = revenue.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.divide(revenue, MARGIN_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        ProfitReport report = new ProfitReport();
        report.setShopId(shopId);
        report.setAmazonOrderId(amazonOrderId);
        report.setSku(sku);
        report.setStatDate(LocalDate.now());
        report.setRevenue(revenue);
        report.setProductCost(cogs);
        report.setFbaFulfillmentFee(fbaFee);
        report.setFbaStorageFee(storageFee);
        report.setReferralFee(referralFee);
        report.setAdCost(adCost);
        report.setVat(vat);
        report.setGrossProfit(grossProfit);
        report.setNetProfit(netProfit);
        report.setNetMargin(netMargin);
        boolean dataComplete = !costMissing && !rateMissing;
        report.setDataComplete(dataComplete);

        log.info("利润计算完成 shopId={}, order={}, sku={}, revenue={}, grossProfit={}, netProfit={}, margin={}, dataComplete={}",
                shopId, amazonOrderId, sku, revenue, grossProfit, netProfit, netMargin, dataComplete);

        return report;
    }

    /**
     * 查询采购单价（缺失返回 null，由调用方标记 dataComplete=false）
     */
    private BigDecimal lookupCogs(Long shopId, String sku) {
        ProductCost cost = productCostMapper.selectOne(new LambdaQueryWrapper<ProductCost>()
                .eq(ProductCost::getShopId, shopId)
                .eq(ProductCost::getSku, sku));
        if (cost == null) {
            log.warn("未找到采购成本记录，标记数据不完整：shopId={}, sku={}", shopId, sku);
            return null;
        }
        return nullToZero(cost.getUnitCost());
    }

    /**
     * 查询 FBA 费率：size_tier=? AND weight_g>=? AND region=?，取最小 weight_g 满足的记录
     */
    private FbaFeeTable lookupFbaFee(String sizeTier, int weightG, String region) {
        return fbaFeeTableMapper.selectOne(new LambdaQueryWrapper<FbaFeeTable>()
                .eq(FbaFeeTable::getSizeTier, sizeTier)
                .ge(FbaFeeTable::getWeightG, weightG)
                .eq(FbaFeeTable::getRegion, region)
                .orderByAsc(FbaFeeTable::getWeightG)
                .last("LIMIT 1"));
    }

    /**
     * 查询类目佣金率（缺失返回 null，由调用方标记 dataComplete=false）
     */
    private BigDecimal lookupReferralFeeRate(String category) {
        if (category == null || category.isEmpty()) {
            log.warn("类目为空，标记数据不完整");
            return null;
        }
        CategoryFeeRate rate = categoryFeeRateMapper.selectOne(new LambdaQueryWrapper<CategoryFeeRate>()
                .eq(CategoryFeeRate::getCategoryName, category));
        if (rate == null) {
            log.warn("未找到类目佣金率，标记数据不完整：category={}", category);
            return null;
        }
        return nullToZero(rate.getReferralFeeRate());
    }

    /**
     * 将店铺级广告总花费按近 30 天订单数分摊到单订单。
     * <ul>
     *   <li>总花费 ≤ 0 或订单数不可得 → 原样返回（保持可观测，不静默吞差异）；</li>
     *   <li>分母使用带 TTL 的本地缓存，避免每条利润消息触发一次 count 查询。</li>
     * </ul>
     */
    private BigDecimal allocateAdCost(Long shopId, BigDecimal shopAdTotal) {
        if (shopAdTotal == null || shopAdTotal.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        long orderCount = countRecentOrders(shopId);
        if (orderCount <= 0) {
            // 无可分摊分母（新店铺/无订单）：保守按全额计，避免广告成本被静默抹零
            return shopAdTotal;
        }
        return shopAdTotal.divide(BigDecimal.valueOf(orderCount), MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 查询店铺近 30 天订单数（带 10 分钟 TTL 缓存）。
     * 查询异常时返回 -1，由调用方回退为不分摊。
     */
    private long countRecentOrders(Long shopId) {
        long now = System.currentTimeMillis();
        OrderCountCacheEntry cached = orderCountCache.get(shopId);
        if (cached != null && cached.expiresAtMs > now) {
            return cached.count;
        }
        try {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                    .eq(Order::getShopId, shopId)
                    .ge(Order::getPurchaseDate, LocalDate.now().minusDays(30).atStartOfDay()));
            long c = count != null ? count : 0L;
            orderCountCache.put(shopId, new OrderCountCacheEntry(c, now + ORDER_COUNT_CACHE_TTL_MS));
            return c;
        } catch (Exception e) {
            log.warn("查询店铺近30天订单数失败，广告费暂不分摊：shopId={}", shopId, e);
            return -1L;
        }
    }

    private BigDecimal nullToZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * 查询店铺广告花费（Feign 调用 amz-service-ad，失败降级为 0）。
     * <p>
     * AdServiceClient 已配置 fallbackFactory，服务不可用时返回 Result.failure；
     * 此处再做一层防御性 try-catch，确保广告服务异常绝不阻断利润计算。
     */
    private BigDecimal fetchAdCost(Long shopId) {
        try {
            Result<Map<String, Object>> resp = adServiceClient.getShopSummary(shopId);
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                log.warn("广告服务返回异常或空数据，广告费降级为 0：shopId={}", shopId);
                return BigDecimal.ZERO;
            }
            BigDecimal cost = toBigDecimal(resp.getData().get("cost"));
            return cost != null ? cost : BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("调用广告服务获取广告花费失败，广告费降级为 0：shopId={}", shopId, e);
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        if (o instanceof Number) {
            return new BigDecimal(((Number) o).toString());
        }
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
