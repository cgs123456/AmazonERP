package com.amz.profit;

import com.amz.client.AdServiceClient;
import com.amz.mapper.CategoryFeeRateMapper;
import com.amz.mapper.FbaFeeTableMapper;
import com.amz.mapper.OrderMapper;
import com.amz.mapper.ProductCostMapper;
import com.amz.model.CategoryFeeRate;
import com.amz.model.FbaFeeTable;
import com.amz.model.ProductCost;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 广告费分摊的窗口一致性（§S33.82）。
 * <p>
 * 从 {@link ProfitCalculatorTest} 拆出：这是「分子与分母必须同窗口」这一个关注点，
 * 与利润公式本身的断言正交。它要 captures 两个协作者的实参并比对，
 * 单独成类后 setUp 只需装配它真正用到的三张表，不必背整套公式夹具。
 * <p>
 * <b>缺陷本体</b>：{@code fetchAdCost} 不带 days，广告服务按默认 7 天窗口返回花费；
 * 分母 {@code countRecentOrders} 查的是近 30 天订单数。于是「7 天的钱」被摊到
 * 「30 天的单」上 —— 每条订单的广告费只有真实值的 7/30 ≈ 23%，
 * 净利系统性高估一整块成本（广告通常占营收 10–30%）。
 * <p>
 * <b>断言方式</b>：钉住「发给广告服务的 days」与「订单查询窗口」<b>数值相等</b>，
 * 而不是两处分别写死 30。两边共享同一个常量这一事实本身才是要守住的东西；
 * 分别写死会让 off-by-one（下界 N 天 vs 含今天 N 天）再次溜过来。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("广告费分摊：分子与分母必须同窗口")
class ProfitAdAllocationWindowTest {

    @Mock
    private ProductCostMapper productCostMapper;
    @Mock
    private FbaFeeTableMapper fbaFeeTableMapper;
    @Mock
    private CategoryFeeRateMapper categoryFeeRateMapper;
    @Mock
    private OrderMapper orderMapper;

    private ProfitCalculator calculator;

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * “can not find lambda cache for this entity”。这里手动注册表元信息，
     * 断言才能读到真实拼出来的查询参数（§S33.81 同款处理）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, com.amz.model.pojo.Order.class);
    }

    @BeforeEach
    void setUp() {
        calculator = new ProfitCalculator();
        ReflectionTestUtils.setField(calculator, "productCostMapper", productCostMapper);
        ReflectionTestUtils.setField(calculator, "fbaFeeTableMapper", fbaFeeTableMapper);
        ReflectionTestUtils.setField(calculator, "categoryFeeRateMapper", categoryFeeRateMapper);
        ReflectionTestUtils.setField(calculator, "orderMapper", orderMapper);
    }

    @Test
    @DisplayName("发给广告服务的 days 必须等于订单数窗口（含今天，同一口径）")
    void adCostNumeratorAndDenominatorShareWindow() {
        when(productCostMapper.selectOne(any())).thenReturn(costOf(new BigDecimal("30")));
        when(fbaFeeTableMapper.selectOne(any())).thenReturn(fbaFeeOf(new BigDecimal("5"), new BigDecimal("2")));
        when(categoryFeeRateMapper.selectOne(any())).thenReturn(rateOf(new BigDecimal("0.15")));
        when(orderMapper.selectCount(any())).thenReturn(10L);
        adTotalOf("300.00");

        calculator.calculate(1L, "AMZ-W", "SKU-W", new BigDecimal("100"),
                "Electronics", "small-standard", 250, "NA", false);

        // 1) 发给广告服务的窗口
        AdServiceClient adClient =
                (AdServiceClient) ReflectionTestUtils.getField(calculator, "adServiceClient");
        ArgumentCaptor<Integer> daysCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(adClient).getShopSummary(eq(1L), daysCaptor.capture());
        int adDays = daysCaptor.getValue();

        // 2) 订单数查询的窗口：从 LambdaQueryWrapper 的参数表里取出实际下界
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> orderCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectCount(orderCaptor.capture());
        // 必须先触发 getCustomSqlSegment()：MyBatis-Plus 参数表惰性填充，
        // 直接取 paramNameValuePairs 会拿到空 Map（§S33.81 踩过同一个坑）。
        orderCaptor.getValue().getCustomSqlSegment();
        LocalDateTime orderSince = (LocalDateTime) orderCaptor.getValue()
                .getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof LocalDateTime)
                .findFirst().orElse(null);
        assertNotNull(orderSince, "订单查询参数表里没有日期下界："
                + orderCaptor.getValue().getParamNameValuePairs());

        // 两边都按「含今天」计：下界到今天的天数 + 1
        long orderDays = ChronoUnit.DAYS.between(orderSince.toLocalDate(), LocalDate.now()) + 1;
        assertEquals(orderDays, adDays,
                "广告花费窗口(" + adDays + "天)与订单数窗口(" + orderDays + "天)必须同值；"
                        + "不同窗口等于把一段时间的钱摊到另一段时间的单上");
    }

    // ------------------------------------------------------------------ helpers

    /** 广告服务返回指定店铺总花费。 */
    private void adTotalOf(String cost) {
        AdServiceClient adClient = mock(AdServiceClient.class);
        Map<String, Object> data = new HashMap<>();
        data.put("cost", new BigDecimal(cost));
        when(adClient.getShopSummary(any(), anyInt())).thenReturn(Result.success(data));
        ReflectionTestUtils.setField(calculator, "adServiceClient", adClient);
    }

    private static ProductCost costOf(BigDecimal unitCost) {
        ProductCost c = new ProductCost();
        c.setUnitCost(unitCost);
        return c;
    }

    private static FbaFeeTable fbaFeeOf(BigDecimal fulfillmentFee, BigDecimal storageFee) {
        FbaFeeTable t = new FbaFeeTable();
        t.setFulfillmentFee(fulfillmentFee);
        t.setStorageFeePerMonth(storageFee);
        return t;
    }

    private static CategoryFeeRate rateOf(BigDecimal rate) {
        CategoryFeeRate r = new CategoryFeeRate();
        r.setReferralFeeRate(rate);
        return r;
    }
}
