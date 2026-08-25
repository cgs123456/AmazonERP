package com.amz.profit;

import com.amz.mapper.CategoryFeeRateMapper;
import com.amz.mapper.FbaFeeTableMapper;
import com.amz.mapper.ProductCostMapper;
import com.amz.model.CategoryFeeRate;
import com.amz.model.FbaFeeTable;
import com.amz.model.ProductCost;
import com.amz.model.ProfitReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 利润核算核心计算器单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>正常利润计算（NA 非欧盟）：grossProfit / netProfit / netMargin 数值精确</li>
 *   <li>欧盟 VAT 20% 场景（区域差异，近似"币种/区域换算"）</li>
 *   <li>零成本（采购成本查不到）：dataComplete=false，cogs 按 0</li>
 *   <li>负利润：收入不足以覆盖各项费用</li>
 *   <li>零收入：所有金额为 0，netMargin 兜底为 0</li>
 *   <li>类目佣金率缺失：dataComplete=false，referralFee 按 0</li>
 *   <li>FBA 费率查不到：fbaFee / storageFee 为 0</li>
 *   <li>revenue 为 null 应按 0 处理</li>
 * </ul>
 */
@DisplayName("ProfitCalculator 利润计算器测试")
class ProfitCalculatorTest {

    private ProductCostMapper productCostMapper;
    private CategoryFeeRateMapper categoryFeeRateMapper;
    private FbaFeeTableMapper fbaFeeTableMapper;
    private ProfitCalculator calculator;

    @BeforeEach
    void setUp() {
        productCostMapper = mock(ProductCostMapper.class);
        categoryFeeRateMapper = mock(CategoryFeeRateMapper.class);
        fbaFeeTableMapper = mock(FbaFeeTableMapper.class);

        calculator = new ProfitCalculator();
        ReflectionTestUtils.setField(calculator, "productCostMapper", productCostMapper);
        ReflectionTestUtils.setField(calculator, "categoryFeeRateMapper", categoryFeeRateMapper);
        ReflectionTestUtils.setField(calculator, "fbaFeeTableMapper", fbaFeeTableMapper);
    }

    /** 构造采购成本记录 */
    private ProductCost costOf(BigDecimal unitCost) {
        ProductCost c = new ProductCost();
        c.setUnitCost(unitCost);
        return c;
    }

    /** 构造类目佣金率记录 */
    private CategoryFeeRate rateOf(BigDecimal rate) {
        CategoryFeeRate r = new CategoryFeeRate();
        r.setReferralFeeRate(rate);
        return r;
    }

    /** 构造 FBA 费率记录 */
    private FbaFeeTable fbaFeeOf(BigDecimal fulfillmentFee, BigDecimal storageFee) {
        FbaFeeTable f = new FbaFeeTable();
        f.setFulfillmentFee(fulfillmentFee);
        f.setStorageFeePerMonth(storageFee);
        return f;
    }

    @Test
    @DisplayName("正常利润计算（NA 非欧盟）：grossProfit/netProfit/netMargin 应精确匹配")
    void testNormalProfitCalculation() {
        // revenue=100, cogs=30, fbaFee=5, referralRate=0.15, storageFee=2
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                1L, "AMZ-001", "SKU-001", new BigDecimal("100"),
                "Electronics", "small-standard", 250, "NA", false);

        // referralFee = 100 × 0.15 = 15.00
        // grossProfit = 100 - 30 - 5 - 15 = 50.00
        // adCost = 0（广告服务未注入，降级为 0），vat = 0（非欧盟）
        // storageFee 按天分摊 = 月费2 ÷ 30 = 0.07
        // netProfit = 50 - 0 - 0 - 0.07 = 49.93
        // netMargin = 49.93 / 100 = 0.4993
        assertEquals(0, new BigDecimal("100.00").compareTo(r.getRevenue()));
        assertEquals(0, new BigDecimal("30").compareTo(r.getProductCost()));
        assertEquals(0, new BigDecimal("5.00").compareTo(r.getFbaFulfillmentFee()));
        assertEquals(0, new BigDecimal("0.07").compareTo(r.getFbaStorageFee()),
                "月度仓储费应按天分摊（÷30）计入订单");
        assertEquals(0, new BigDecimal("15.00").compareTo(r.getReferralFee()));
        assertEquals(0, new BigDecimal("0.00").compareTo(r.getAdCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getVat()), "非欧盟 VAT 应为 0");
        assertEquals(0, new BigDecimal("50.00").compareTo(r.getGrossProfit()));
        assertEquals(0, new BigDecimal("49.93").compareTo(r.getNetProfit()));
        assertEquals(0, new BigDecimal("0.4993").compareTo(r.getNetMargin()));
        assertTrue(r.getDataComplete(), "成本与佣金率齐全时 dataComplete 应为 true");
    }

    @Test
    @DisplayName("欧盟站点（isEU=true）：VAT 应按收入 20% 计入净利扣减")
    void testEuVatDeduction() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                1L, "AMZ-EU", "SKU-EU", new BigDecimal("100"),
                "Electronics", "small-standard", 250, "EU", true);

        // vat = 含税收入 100 × 0.20 / 1.20 = 16.67（价内税还原，非全额 20%）
        // grossProfit = 50.00（同非欧盟，VAT 不影响毛利）
        // netProfit = 50 - 0 - 16.67 - 0.07 = 33.26
        // netMargin = 33.26 / 100 = 0.3326
        assertEquals(0, new BigDecimal("16.67").compareTo(r.getVat()),
                "欧盟 VAT 应按含税价还原税额（revenue × r/(1+r)）");
        assertEquals(0, new BigDecimal("50.00").compareTo(r.getGrossProfit()));
        assertEquals(0, new BigDecimal("33.26").compareTo(r.getNetProfit()));
        assertEquals(0, new BigDecimal("0.3326").compareTo(r.getNetMargin()));
    }

    @Test
    @DisplayName("零成本（采购成本查不到）：cogs 按 0，dataComplete=false")
    void testZeroCostMissingCogs() {
        when(productCostMapper.selectOne(any())).thenReturn(null);
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                1L, "AMZ-NOCOST", "SKU-NOCOST", new BigDecimal("100"),
                "Electronics", "small-standard", 250, "NA", false);

        // cogs 缺失 → nullToZero = 0
        // grossProfit = 100 - 0 - 5 - 15 = 80.00
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getProductCost()), "采购成本查不到时按 0 处理");
        assertEquals(0, new BigDecimal("80.00").compareTo(r.getGrossProfit()));
        assertFalse(r.getDataComplete(), "采购成本缺失应标记 dataComplete=false");
    }

    @Test
    @DisplayName("负利润：收入不足以覆盖各项费用时 netProfit 应为负数")
    void testNegativeProfit() {
        // revenue=10, cogs=20, fbaFee=5, referralRate=0.15 → referralFee=1.50
        // grossProfit = 10 - 20 - 5 - 1.50 = -16.50
        // storageFee 按天分摊 = 2 ÷ 30 = 0.07
        // netProfit = -16.50 - 0 - 0 - 0.07 = -16.57
        // netMargin = -16.57 / 10 = -1.6570
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("20")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                1L, "AMZ-NEG", "SKU-NEG", new BigDecimal("10"),
                "Electronics", "small-standard", 250, "NA", false);

        assertTrue(r.getGrossProfit().compareTo(BigDecimal.ZERO) < 0, "毛利应为负");
        assertTrue(r.getNetProfit().compareTo(BigDecimal.ZERO) < 0, "净利应为负");
        assertEquals(0, new BigDecimal("-16.50").compareTo(r.getGrossProfit()));
        assertEquals(0, new BigDecimal("-16.57").compareTo(r.getNetProfit()));
        assertEquals(0, new BigDecimal("-1.6570").compareTo(r.getNetMargin()));
        assertTrue(r.getDataComplete());
    }

    @Test
    @DisplayName("零收入：所有费用为 0，netMargin 兜底为 0（避免除零）")
    void testZeroRevenue() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                1L, "AMZ-ZERO", "SKU-ZERO", BigDecimal.ZERO,
                "Electronics", "small-standard", 250, "NA", false);

        // revenue=0 → referralFee=0×0.15=0, vat=0
        // grossProfit = 0 - 30 - 5 - 0 = -35.00
        // storageFee 按天分摊 = 0.07 → netProfit = -35 - 0 - 0 - 0.07 = -35.07
        // netMargin: revenue=0 时兜底返回 0
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getRevenue()));
        assertEquals(0, new BigDecimal("0.00").compareTo(r.getReferralFee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getVat()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getNetMargin()), "零收入时 netMargin 应兜底为 0");
        assertTrue(r.getDataComplete());
    }

    @Test
    @DisplayName("revenue 为 null 时应按 0 处理，不抛 NPE")
    void testNullRevenueTreatedAsZero() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                1L, "AMZ-NULL", "SKU-NULL", null,
                "Electronics", "small-standard", 250, "NA", false);

        assertEquals(0, BigDecimal.ZERO.compareTo(r.getRevenue()), "null 收入应被替换为 0");
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getNetMargin()));
    }

    @Test
    @DisplayName("类目佣金率缺失：referralFee 按 0，dataComplete=false")
    void testMissingReferralFeeRate() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(null);

        ProfitReport r = calculator.calculate(
                1L, "AMZ-NORATE", "SKU-NORATE", new BigDecimal("100"),
                "Electronics", "small-standard", 250, "NA", false);

        // referralFeeRate 缺失 → 0，referralFee = 100 × 0 = 0
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getReferralFee()));
        assertFalse(r.getDataComplete(), "佣金率缺失应标记 dataComplete=false");
        // grossProfit = 100 - 30 - 5 - 0 = 65.00
        assertEquals(0, new BigDecimal("65.00").compareTo(r.getGrossProfit()));
    }

    @Test
    @DisplayName("类目为空字符串：查表跳过，佣金率按 null 处理，dataComplete=false")
    void testEmptyCategory() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        // category="" 时 lookupReferralFeeRate 直接返回 null，不调用 mapper
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(null);

        ProfitReport r = calculator.calculate(
                1L, "AMZ-EMPTYCAT", "SKU-EMPTY", new BigDecimal("100"),
                "", "small-standard", 250, "NA", false);

        assertEquals(0, BigDecimal.ZERO.compareTo(r.getReferralFee()));
        assertFalse(r.getDataComplete());
    }

    @Test
    @DisplayName("FBA 费率查不到：fbaFee 与 storageFee 均为 0")
    void testMissingFbaFee() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(null);
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                1L, "AMZ-NOFBA", "SKU-NOFBA", new BigDecimal("100"),
                "Electronics", "small-standard", 250, "NA", false);

        // fbaFee=0, storageFee=0
        // grossProfit = 100 - 30 - 0 - 15 = 55.00
        // netProfit = 55 - 0 - 0 - 0 = 55.00
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getFbaFulfillmentFee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getFbaStorageFee()));
        assertEquals(0, new BigDecimal("55.00").compareTo(r.getGrossProfit()));
        assertEquals(0, new BigDecimal("55.00").compareTo(r.getNetProfit()));
    }

    @Test
    @DisplayName("返回的 ProfitReport 应正确回填订单与 SKU 元信息")
    void testReportMetadataBackfilled() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));

        ProfitReport r = calculator.calculate(
                7L, "ORDER-XYZ", "SKU-META", new BigDecimal("100"),
                "Electronics", "small-standard", 250, "NA", false);

        assertEquals(7L, r.getShopId());
        assertEquals("ORDER-XYZ", r.getAmazonOrderId());
        assertEquals("SKU-META", r.getSku());
        assertNotNull(r.getStatDate(), "statDate 应被设置为今天");
    }
}
