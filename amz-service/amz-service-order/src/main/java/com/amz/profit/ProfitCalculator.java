package com.amz.profit;

import com.amz.mapper.CategoryFeeRateMapper;
import com.amz.mapper.FbaFeeTableMapper;
import com.amz.mapper.ProductCostMapper;
import com.amz.model.CategoryFeeRate;
import com.amz.model.FbaFeeTable;
import com.amz.model.ProductCost;
import com.amz.model.ProfitReport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 利润核算核心计算器
 * 按蓝图文档公式：
 *   grossProfit = revenue - cogs - fbaFee - referralFee
 *   netProfit   = grossProfit - adCost - vat - storageFee
 *   netMargin   = netProfit / revenue
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

    private static final BigDecimal EU_VAT_RATE = new BigDecimal("0.20");

    private static final int MONEY_SCALE = 2;
    private static final int MARGIN_SCALE = 4;

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

        // 1. 采购成本 cogs（取 unitCost）
        BigDecimal cogs = lookupCogs(shopId, sku);

        // 2. FBA 履约费 + 仓储费（取最小满足 weight_g>=? 的记录）
        FbaFeeTable fbaFeeTable = lookupFbaFee(sizeTier, weightG, region);
        BigDecimal fbaFee = fbaFeeTable != null ? nullToZero(fbaFeeTable.getFulfillmentFee()) : BigDecimal.ZERO;
        BigDecimal storageFee = fbaFeeTable != null ? nullToZero(fbaFeeTable.getStorageFeePerMonth()) : BigDecimal.ZERO;

        // 3. 平台佣金 referralFee = revenue × referralFeeRate
        BigDecimal referralFeeRate = lookupReferralFeeRate(category);
        BigDecimal referralFee = revenue.multiply(referralFeeRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 4. 广告费（TODO: 接入广告 API）
        BigDecimal adCost = BigDecimal.ZERO;

        // 5. VAT（欧盟 20%）
        BigDecimal vat = isEU ? revenue.multiply(EU_VAT_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;

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

        log.info("利润计算完成 shopId={}, order={}, sku={}, revenue={}, grossProfit={}, netProfit={}, margin={}",
                shopId, amazonOrderId, sku, revenue, grossProfit, netProfit, netMargin);

        return report;
    }

    /**
     * 查询采购单价（缺失兜底 0）
     */
    private BigDecimal lookupCogs(Long shopId, String sku) {
        ProductCost cost = productCostMapper.selectOne(new LambdaQueryWrapper<ProductCost>()
                .eq(ProductCost::getShopId, shopId)
                .eq(ProductCost::getSku, sku));
        if (cost == null) {
            log.warn("未找到采购成本记录，使用 0 兜底：shopId={}, sku={}", shopId, sku);
            return BigDecimal.ZERO;
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
     * 查询类目佣金率（缺失兜底 0）
     */
    private BigDecimal lookupReferralFeeRate(String category) {
        CategoryFeeRate rate = categoryFeeRateMapper.selectOne(new LambdaQueryWrapper<CategoryFeeRate>()
                .eq(CategoryFeeRate::getCategoryName, category));
        if (rate == null) {
            log.warn("未找到类目佣金率，使用 0 兜底：category={}", category);
            return BigDecimal.ZERO;
        }
        return nullToZero(rate.getReferralFeeRate());
    }

    private BigDecimal nullToZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
