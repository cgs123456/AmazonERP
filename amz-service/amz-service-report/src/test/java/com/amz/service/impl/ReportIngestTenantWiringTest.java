package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.BusinessOverviewMapper;
import com.amz.mapper.CostAllocationMapper;
import com.amz.mapper.InventoryTurnoverMapper;
import com.amz.mapper.ProfitDetailMapper;
import com.amz.mapper.ProfitSnapshotMapper;
import com.amz.mapper.SalesDailyMapper;
import com.amz.model.BusinessOverview;
import com.amz.model.CostAllocation;
import com.amz.model.InventoryTurnover;
import com.amz.model.ProfitDetail;
import com.amz.model.SalesDaily;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 5 个报表摄取写入路径的归属兜底接线。
 * <p>
 * 锁的是「守卫真的挂在每个写入口上，越权时一行都不落库」——只测 {@code ReportTenantGuard}
 * 本身不够：漏接任何一个方法，那个端点就还是空转。{@code @ShopScoped} 在这些端点上不起作用
 * （shopId 只存在于 {@code @RequestBody} 实体里，切面只认同名 PathVariable/RequestParam），
 * 所以这里必须逐个方法点名。
 */
@DisplayName("报表摄取端点：跨店铺写入必须被拒且零落库")
@ExtendWith(MockitoExtension.class)
class ReportIngestTenantWiringTest {

    @Mock
    private ProfitDetailMapper profitDetailMapper;
    @Mock
    private InventoryTurnoverMapper inventoryTurnoverMapper;
    @Mock
    private SalesDailyMapper salesDailyMapper;
    @Mock
    private BusinessOverviewMapper businessOverviewMapper;
    @Mock
    private ProfitSnapshotMapper profitSnapshotMapper;
    @Mock
    private CostAllocationMapper costAllocationMapper;
    @Mock
    private ObjectMapper objectMapper;

    @Spy
    private ReportTenantGuard tenantGuard = new ReportTenantGuard();

    @InjectMocks
    private ReportUpgradeServiceImpl upgradeService;
    @InjectMocks
    private RealtimeProfitServiceImpl realtimeService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("利润明细写别人店铺：抛出且 mapper 一次都没被碰")
    void profitDetailWriteIsScopedToCallerShops() {
        ProfitDetail detail = new ProfitDetail();
        detail.setShopId(2L);
        detail.setAmazonOrderId("114-1");

        assertThrows(CodeErrorException.class, () -> upgradeService.saveProfitDetail(detail));
        verifyNoInteractions(profitDetailMapper);
    }

    @Test
    @DisplayName("库存周转写别人店铺：抛出且零落库")
    void inventoryTurnoverWriteIsScopedToCallerShops() {
        InventoryTurnover turnover = new InventoryTurnover();
        turnover.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> upgradeService.saveInventoryTurnover(turnover));
        verifyNoInteractions(inventoryTurnoverMapper);
    }

    @Test
    @DisplayName("销售日报写别人店铺：抛出且零落库")
    void salesDailyWriteIsScopedToCallerShops() {
        SalesDaily daily = new SalesDaily();
        daily.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> upgradeService.saveSalesDaily(daily));
        verifyNoInteractions(salesDailyMapper);
    }

    @Test
    @DisplayName("经营概览写别人店铺：抛出且零落库")
    void businessOverviewWriteIsScopedToCallerShops() {
        BusinessOverview overview = new BusinessOverview();
        overview.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> upgradeService.saveBusinessOverview(overview));
        verifyNoInteractions(businessOverviewMapper);
    }

    @Test
    @DisplayName("费用分摊写别人店铺：抛出且零落库（旧实现连 shopId 是否为空都不查）")
    void costAllocationWriteIsScopedToCallerShops() {
        CostAllocation allocation = new CostAllocation();
        allocation.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> realtimeService.saveAllocation(allocation));
        verifyNoInteractions(costAllocationMapper);
    }

    @Test
    @DisplayName("费用分摊不带 shopId 也不能插出去")
    void costAllocationWithoutShopIdIsRejected() {
        CostAllocation allocation = new CostAllocation();

        assertThrows(Exception.class, () -> realtimeService.saveAllocation(allocation));
        verifyNoInteractions(costAllocationMapper);
    }
}
