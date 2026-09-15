package com.amz.service.impl;

import com.amz.dto.FreightCostBoard;
import com.amz.dto.QuoteBoard;
import com.amz.dto.ReceiptBoard;
import com.amz.dto.TransferBoard;
import com.amz.mapper.CarrierQuoteMapper;
import com.amz.mapper.FbaReceiptDiscrepancyMapper;
import com.amz.mapper.FreightAllocationMapper;
import com.amz.mapper.InventoryTransferMapper;
import com.amz.mapper.ShipmentMapper;
import com.amz.model.CarrierQuote;
import com.amz.model.FbaReceiptDiscrepancy;
import com.amz.model.FreightAllocation;
import com.amz.model.InventoryTransfer;
import com.amz.model.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 物流运营子域看板聚合单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 这一层的价值在于<b>口径正确且可解释</b>，因此测试聚焦三类容易算错的地方：
 * <ul>
 *   <li><b>有效性判定</b>：报价「状态为 ACTIVE」不等于「报价有效」（可能已过失效日期）；</li>
 *   <li><b>无样本 vs 0</b>：平均单件成本、覆盖率等指标在没有数据时必须为 null，不能是 0；</li>
 *   <li><b>不可抵消</b>：签收差异率按绝对差异之和算，避免少收与多收互相抵消掩盖问题。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("物流运营子域看板聚合单元测试")
class LogisticsOpsDashboardServiceImplTest {

    private static final long SHOP_ID = 1L;

    @Mock
    private CarrierQuoteMapper carrierQuoteMapper;

    @Mock
    private InventoryTransferMapper inventoryTransferMapper;

    @Mock
    private FreightAllocationMapper freightAllocationMapper;

    @Mock
    private FbaReceiptDiscrepancyMapper fbaReceiptDiscrepancyMapper;

    @Mock
    private ShipmentMapper shipmentMapper;

    @InjectMocks
    private LogisticsOpsDashboardServiceImpl service;

    @BeforeEach
    void setUpThresholds() {
        // @Value 字段在没有 Spring 容器时不会被注入，手动补齐业务阈值
        ReflectionTestUtils.setField(service, "transferStaleDays", 7);
        ReflectionTestUtils.setField(service, "transferApprovalStaleDays", 3);
    }

    // ================================================================ 报价看板

    @Test
    @DisplayName("报价看板：区分「有效」「已失效未收口」「未填单价」三类，且只统计 ACTIVE 的未填价")
    void quoteBoardSeparatesValidity() {
        CarrierQuote valid = quote("COSCO", "SEA", LocalDate.now().plusDays(60));
        CarrierQuote expiringSoon = quote("Maersk", "SEA", LocalDate.now().plusDays(10));
        CarrierQuote stale = quote("OldLine", "SEA", LocalDate.now().minusDays(1));
        CarrierQuote noPrice = quote("NoPrice", "SEA", LocalDate.now().plusDays(60));
        noPrice.setPricePerKg(null);
        noPrice.setPricePerCbm(null);
        CarrierQuote disabled = quote("Gone", "SEA", LocalDate.now().minusDays(30));
        disabled.setStatus("DISABLED");
        disabled.setPricePerKg(null);
        disabled.setPricePerCbm(null);

        when(carrierQuoteMapper.selectList(any())).thenReturn(
                List.of(valid, expiringSoon, stale, noPrice, disabled));

        QuoteBoard board = service.quoteBoard(SHOP_ID);

        assertEquals(5, board.getTotalQuotes());
        assertEquals(3, board.getValidQuotes(), "有效期内的 ACTIVE 报价才算有效");
        assertEquals(1, board.getStaleByDate(), "已过失效日期但仍是 ACTIVE 的单独计数");
        assertEquals(1, board.getInactiveQuotes());
        assertEquals(1, board.getExpiringIn30Days(), "只有 10 天后失效的那条进入即将失效");
        // 已停用且未填价的报价不该计入：它本来就无法参与比价，混进来会撑大这个数字
        assertEquals(1, board.getUnpricedQuotes());
        assertEquals(3, board.getCarrierCount(), "只有有效报价的承运商算覆盖");
        assertTrue(board.getWarnings().toString().contains("失效日期"), board.getWarnings().toString());
        assertTrue(board.getWarnings().toString().contains("未填写任何单价"),
                board.getWarnings().toString());
    }

    @Test
    @DisplayName("报价看板：单一来源航线被标出并排在首位，且统计到提示里")
    void quoteBoardFlagsSingleSourceRoute() {
        // 同一航线两家 → 有议价空间
        CarrierQuote a = quote("COSCO", "SEA", LocalDate.now().plusDays(30));
        a.setOriginPort("Shenzhen");
        a.setDestinationPort("LA");
        CarrierQuote b = quote("Maersk", "SEA", LocalDate.now().plusDays(30));
        b.setOriginPort("Shenzhen");
        b.setDestinationPort("LA");
        b.setPricePerKg(new BigDecimal("5.00"));
        // 另一条航线只有一家 → 单一来源
        CarrierQuote c = quote("DHL", "EXPRESS", LocalDate.now().plusDays(30));
        c.setOriginPort("Ningbo");
        c.setDestinationPort("NYC");

        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of(a, b, c));

        QuoteBoard board = service.quoteBoard(SHOP_ID);

        assertEquals(2, board.getRouteCount());
        QuoteBoard.RouteCoverage first = board.getRoutes().get(0);
        assertTrue(first.isSingleSource(), "单一来源航线应排在第一位");
        assertEquals("Ningbo", first.getOriginPort());
        assertEquals(1, first.getCarrierCount());

        QuoteBoard.RouteCoverage multi = board.getRoutes().get(1);
        assertEquals(2, multi.getCarrierCount());
        // 3.50 → 5.00 的溢价率 = 0.4286
        assertEquals(new BigDecimal("0.4286"), multi.getPriceSpreadRate());
    }

    @Test
    @DisplayName("报价看板：多币种航线标为 MIXED 并放弃溢价率，避免把两个币种当同一单位比")
    void quoteBoardMarksMixedCurrencyRoute() {
        CarrierQuote usd = quote("COSCO", "SEA", LocalDate.now().plusDays(30));
        usd.setCurrency("USD");
        usd.setPricePerKg(new BigDecimal("3.00"));
        CarrierQuote cny = quote("SF", "SEA", LocalDate.now().plusDays(30));
        cny.setCurrency("CNY");
        cny.setPricePerKg(new BigDecimal("20.00"));

        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of(usd, cny));

        QuoteBoard board = service.quoteBoard(SHOP_ID);

        QuoteBoard.RouteCoverage route = board.getRoutes().get(0);
        assertEquals("MIXED", route.getCurrency());
        assertNull(route.getPriceSpreadRate(), "跨币种算溢价率没有意义");
        assertEquals(2, board.getByCurrency().size());
        assertTrue(board.getWarnings().toString().contains("币种"));
    }

    @Test
    @DisplayName("报价看板：一条报价都没有时给出明确提示，而不是默默返回全 0")
    void quoteBoardWarnsWhenEmpty() {
        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of());

        QuoteBoard board = service.quoteBoard(SHOP_ID);

        assertEquals(0, board.getValidQuotes());
        assertTrue(board.getWarnings().toString().contains("尚未登记任何物流商报价"),
                board.getWarnings().toString());
    }

    // ================================================================ 调拨看板

    @Test
    @DisplayName("调拨看板：状态计数补齐全部状态，在途超期进入风险清单")
    void transferBoardCountsStatusAndFlagsStale() {
        InventoryTransfer draft = transfer("TRF-1", "DRAFT", 5);
        InventoryTransfer approved = transfer("TRF-2", "APPROVED", 1);
        InventoryTransfer stale = transfer("TRF-3", "IN_TRANSIT", 12);
        stale.setTrackingNo("SF123");
        InventoryTransfer fresh = transfer("TRF-4", "IN_TRANSIT", 1);
        fresh.setTrackingNo("SF456");
        InventoryTransfer noTracking = transfer("TRF-5", "IN_TRANSIT", 2);
        noTracking.setTrackingNo(null);
        InventoryTransfer received = transfer("TRF-6", "RECEIVED", 20);

        when(inventoryTransferMapper.selectList(any())).thenReturn(
                List.of(draft, approved, stale, fresh, noTracking, received));

        TransferBoard board = service.transferBoard(SHOP_ID);

        assertEquals(6, board.getTotal());
        // 全部合法状态都要有键，前端渲染不必做缺省兜底
        assertEquals(6, board.getByStatus().size());
        assertEquals(1, board.getByStatus().get("DRAFT"));
        assertEquals(3, board.getByStatus().get("IN_TRANSIT"));
        assertEquals(1, board.getPendingApproval(), "草稿计入待审批");
        assertEquals(1, board.getApprovedNotShipped());
        assertEquals(1, board.getStaleInTransit(), "只有超过 7 天的在途算卡单");
        assertEquals(0, board.getCancelled());
        assertEquals(7, board.getStaleThresholdDays());

        List<String> riskTypes = board.getRisks().stream()
                .map(TransferBoard.TransferRisk::getType).toList();
        assertTrue(riskTypes.contains("STALE_IN_TRANSIT"), riskTypes.toString());
        assertTrue(riskTypes.contains("MISSING_TRACKING_NO"), riskTypes.toString());

        // 未填运单号的在途单要提示补录，否则后续无法自动跟单
        TransferBoard.TransferRisk missing = board.getRisks().stream()
                .filter(r -> "MISSING_TRACKING_NO".equals(r.getType()))
                .findFirst().orElseThrow();
        assertEquals("TRF-5", missing.getTransferNo());
        assertEquals("MEDIUM", missing.getSeverity());
    }

    @Test
    @DisplayName("调拨看板：待审批超期单独成一条风险，与在途卡单区分开")
    void transferBoardFlagsSlowApproval() {
        InventoryTransfer slow = transfer("TRF-9", "PENDING_APPROVAL", 6);
        when(inventoryTransferMapper.selectList(any())).thenReturn(List.of(slow));

        TransferBoard board = service.transferBoard(SHOP_ID);

        assertEquals(1, board.getRisks().size());
        assertEquals("PENDING_APPROVAL_TOO_LONG", board.getRisks().get(0).getType());
        assertEquals(6, board.getRisks().get(0).getDaysSinceCreated());
    }

    @Test
    @DisplayName("调拨看板：无数据时返回 0 与空清单，不抛异常")
    void transferBoardHandlesEmpty() {
        when(inventoryTransferMapper.selectList(any())).thenReturn(List.of());

        TransferBoard board = service.transferBoard(SHOP_ID);

        assertEquals(0, board.getTotal());
        assertNotNull(board.getByStatus());
        assertEquals(6, board.getByStatus().size(), "无数据时状态键也要补齐");
        assertTrue(board.getRisks().isEmpty());
        assertEquals(0, board.getTotalShippingCost().compareTo(BigDecimal.ZERO));
    }

    // ================================================================ 头程成本看板

    @Test
    @DisplayName("头程成本看板：算出成本构成与覆盖率，未核算货件按「有费用未摊」优先排列")
    void freightBoardComputesCoverageAndPrioritisesDeclaredFreight() {
        Shipment covered = shipment(101L, "SHP-101");
        Shipment uncoveredWithFreight = shipment(102L, "SHP-102");
        uncoveredWithFreight.setFreightCost(new BigDecimal("8000.00"));
        Shipment uncoveredNoFreight = shipment(103L, "SHP-103");

        FreightAllocation a = allocation(101L, new BigDecimal("1000.00"), new BigDecimal("200.00"), 100);
        FreightAllocation b = allocation(101L, new BigDecimal("500.00"), new BigDecimal("100.00"), 50);

        when(shipmentMapper.selectList(any())).thenReturn(
                List.of(covered, uncoveredWithFreight, uncoveredNoFreight));
        when(freightAllocationMapper.selectList(any())).thenReturn(List.of(a, b));

        FreightCostBoard board = service.freightCostBoard(SHOP_ID);

        assertEquals(2, board.getTotalAllocationRows());
        assertEquals(150L, board.getTotalQuantity());
        assertEquals(1, board.getCoveredShipments());
        assertEquals(2, board.getUncoveredShipments());
        // 覆盖率 1/3，保留 4 位
        assertEquals(new BigDecimal("0.3333"), board.getCoverageRate());
        // 总成本 = (1000+200) + (500+100) = 1800，单件 = 1800 / 150 = 12.00
        assertEquals(0, board.getTotalCost().compareTo(new BigDecimal("1800.00")));
        assertEquals(0, board.getAvgUnitCost().compareTo(new BigDecimal("12.00")));

        assertEquals(2, board.getUncoveredList().size());
        assertTrue(board.getUncoveredList().get(0).isHasDeclaredFreight(),
                "已登记运费却没分摊的货件排最前——钱花了但没进成本，利润会被高估");
        assertTrue(board.getWarnings().toString().contains("未做头程分摊"), board.getWarnings().toString());
    }

    @Test
    @DisplayName("头程成本看板：没有任何分摊明细时单件成本为 null（不是 0）")
    void freightBoardReturnsNullUnitCostWhenNoAllocation() {
        Shipment s = shipment(201L, "SHP-201");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(s));
        when(freightAllocationMapper.selectList(any())).thenReturn(List.of());

        FreightCostBoard board = service.freightCostBoard(SHOP_ID);

        assertEquals(1, board.getUncoveredShipments());
        assertEquals(new BigDecimal("0.0000"), board.getCoverageRate());
        // 0 会被读成「成本极低」，实际含义是「没有可摊的数据」，两者必须能区分
        assertNull(board.getAvgUnitCost());
        assertTrue(board.getWarnings().toString().contains("尚无任何头程分摊明细"),
                board.getWarnings().toString());
    }

    @Test
    @DisplayName("头程成本看板：无货件时覆盖率为 null，与「零覆盖」区分开")
    void freightBoardNullCoverageWithoutShipments() {
        when(shipmentMapper.selectList(any())).thenReturn(List.of());
        when(freightAllocationMapper.selectList(any())).thenReturn(List.of());

        FreightCostBoard board = service.freightCostBoard(SHOP_ID);

        assertNull(board.getCoverageRate());
        assertEquals(0, board.getUncoveredShipments());
    }

    @Test
    @DisplayName("头程成本看板：单件成本 Top 按降序，混用分摊方法时给出提示")
    void freightBoardSortsTopUnitCostAndWarnsOnMixedMethods() {
        Shipment s = shipment(301L, "SHP-301");
        FreightAllocation low = allocation(301L, new BigDecimal("100.00"), BigDecimal.ZERO, 100);
        low.setUnitCost(new BigDecimal("1.00"));
        low.setAllocationMethod("WEIGHT");
        FreightAllocation high = allocation(301L, new BigDecimal("900.00"), BigDecimal.ZERO, 10);
        high.setUnitCost(new BigDecimal("90.00"));
        high.setAllocationMethod("VOLUME");

        when(shipmentMapper.selectList(any())).thenReturn(List.of(s));
        when(freightAllocationMapper.selectList(any())).thenReturn(List.of(low, high));

        FreightCostBoard board = service.freightCostBoard(SHOP_ID);

        assertEquals(new BigDecimal("90.00"), board.getTopUnitCostItems().get(0).getUnitCost());
        assertEquals("SHP-301", board.getTopUnitCostItems().get(0).getShipmentNo());
        assertTrue(board.getWarnings().toString().contains("分摊方法"), board.getWarnings().toString());
    }

    // ================================================================ 签收差异看板

    @Test
    @DisplayName("签收差异看板：少收 / 多收 / 无差异分别计数，未结案少收单独汇总")
    void receiptBoardClassifiesDifferences() {
        // 少收 4 件，待处理
        FbaReceiptDiscrepancy shortPending = discrepancy(401L, 100, 96, "PENDING");
        // 少收 6 件，核查中
        FbaReceiptDiscrepancy shortInvestigating = discrepancy(402L, 200, 194, "INVESTIGATING");
        // 少收 2 件，已结案（不应计入未结案少收）
        FbaReceiptDiscrepancy shortResolved = discrepancy(403L, 50, 48, "RESOLVED");
        // 多收 8 件
        FbaReceiptDiscrepancy over = discrepancy(404L, 100, 108, "PENDING");
        // 无差异
        FbaReceiptDiscrepancy matched = discrepancy(405L, 30, 30, "RESOLVED");

        when(fbaReceiptDiscrepancyMapper.selectList(any())).thenReturn(
                List.of(shortPending, shortInvestigating, shortResolved, over, matched));
        when(shipmentMapper.selectList(any())).thenReturn(
                List.of(shipment(401L, "SHP-401"), shipment(402L, "SHP-402")));

        ReceiptBoard board = service.receiptBoard(SHOP_ID);

        assertEquals(5, board.getTotal());
        assertEquals(1, board.getMatched(), "0 差异单独计数，不算作差异");
        assertEquals(3, board.getShortageRows());
        assertEquals(1, board.getOverreceivedRows());
        assertEquals(12, board.getShortageUnits(), "少收合计 4 + 6 + 2");
        assertEquals(8, board.getOverreceivedUnits());
        assertEquals(10, board.getOpenShortageUnits(), "只有待处理与核查中的 4 + 6 计入未结案");

        assertEquals(2, board.getPending(), "待处理：少收 4 件的那条与多收 8 件的那条");
        assertEquals(1, board.getInvestigating());
        assertEquals(2, board.getResolved(), "已结案的少收 2 件 + 无差异那条");
    }

    @Test
    @DisplayName("签收差异看板：差异率按绝对差异之和计算，避免少收与多收互相抵消")
    void receiptBoardRateDoesNotNetOff() {
        // 一批少收 100、另一批多收 100：净差异为 0，但问题量级很大
        FbaReceiptDiscrepancy shortOne = discrepancy(501L, 1000, 900, "PENDING");
        FbaReceiptDiscrepancy overOne = discrepancy(502L, 1000, 1100, "PENDING");

        when(fbaReceiptDiscrepancyMapper.selectList(any())).thenReturn(List.of(shortOne, overOne));
        when(shipmentMapper.selectList(any())).thenReturn(List.of());

        ReceiptBoard board = service.receiptBoard(SHOP_ID);

        assertEquals(0, board.getTotalDifference(), "净差异确实是 0");
        // 绝对差异之和 200 / 应收合计 2000 = 0.1，若按净差异算会得到 0
        assertEquals(new BigDecimal("0.1000"), board.getDiscrepancyRate(),
                "差异率必须按绝对差异之和算，否则量级很大的问题会被掩盖");
        assertTrue(board.getWarnings().toString().contains("同时存在少收与多收"),
                board.getWarnings().toString());
    }

    @Test
    @DisplayName("签收差异看板：无记录时差异率为 null，不是 0")
    void receiptBoardNullRateWhenEmpty() {
        when(fbaReceiptDiscrepancyMapper.selectList(any())).thenReturn(List.of());

        ReceiptBoard board = service.receiptBoard(SHOP_ID);

        assertEquals(0, board.getTotal());
        assertNull(board.getDiscrepancyRate());
        assertTrue(board.getPendingItems().isEmpty());
    }

    @Test
    @DisplayName("签收差异看板：按 ASIN 归并，少收最多的排最前，待处理清单带上货件号")
    void receiptBoardGroupsByAsin() {
        FbaReceiptDiscrepancy a1 = discrepancy(601L, 100, 90, "PENDING");
        a1.setAsin("B0AAA");
        FbaReceiptDiscrepancy a2 = discrepancy(602L, 100, 95, "PENDING");
        a2.setAsin("B0AAA");
        FbaReceiptDiscrepancy b1 = discrepancy(603L, 100, 97, "INVESTIGATING");
        b1.setAsin("B0BBB");

        when(fbaReceiptDiscrepancyMapper.selectList(any())).thenReturn(List.of(a1, a2, b1));
        when(shipmentMapper.selectList(any())).thenReturn(
                List.of(shipment(601L, "SHP-601"), shipment(603L, "SHP-603")));

        ReceiptBoard board = service.receiptBoard(SHOP_ID);

        assertEquals(2, board.getTopShortageAsins().size());
        ReceiptBoard.AsinShortage top = board.getTopShortageAsins().get(0);
        assertEquals("B0AAA", top.getAsin());
        assertEquals(15, top.getShortageUnits());
        assertEquals(2, top.getShipmentCount());
        assertEquals(-15, top.getNetDifference());

        // 待处理清单里必须能看到货件号，否则要去别处反查
        assertEquals(3, board.getPendingItems().size());
        assertTrue(board.getPendingItems().stream()
                .anyMatch(i -> "SHP-601".equals(i.getShipmentNo())));
        // 差异绝对值大的排最前；少收为负数（实收 − 应收）
        assertEquals(-10, board.getPendingItems().get(0).getDifference());
    }

    // ================================================================ 构造工具

    private CarrierQuote quote(String carrier, String serviceType, LocalDate expiry) {
        CarrierQuote q = new CarrierQuote();
        q.setId(1L);
        q.setShopId(SHOP_ID);
        q.setCarrierName(carrier);
        q.setServiceType(serviceType);
        q.setOriginPort("Shenzhen");
        q.setDestinationPort("LA");
        q.setTransitDays(25);
        q.setPricePerKg(new BigDecimal("3.50"));
        q.setPricePerCbm(new BigDecimal("850.00"));
        q.setMinCharge(new BigDecimal("500.00"));
        q.setFuelSurchargeRate(new BigDecimal("15.00"));
        q.setCurrency("USD");
        q.setExpiryDate(expiry);
        q.setStatus("ACTIVE");
        return q;
    }

    private InventoryTransfer transfer(String no, String status, int createdDaysAgo) {
        InventoryTransfer t = new InventoryTransfer();
        t.setId((long) no.hashCode());
        t.setShopId(SHOP_ID);
        t.setTransferNo(no);
        t.setFromWarehouseId(1L);
        t.setToWarehouseId(2L);
        t.setAsin("B0TEST");
        t.setSku("SKU-1");
        t.setQuantity(10);
        t.setStatus(status);
        t.setShippingCost(new BigDecimal("80.00"));
        t.setCreateTime(LocalDateTime.now().minusDays(createdDaysAgo));
        return t;
    }

    private Shipment shipment(Long id, String no) {
        Shipment s = new Shipment();
        s.setId(id);
        s.setShopId(SHOP_ID);
        s.setShipmentNo(no);
        s.setCarrier("COSCO");
        s.setStatus("IN_TRANSIT");
        s.setEta("2026-10-01");
        return s;
    }

    private FreightAllocation allocation(Long shipmentId, BigDecimal freight, BigDecimal duty, int quantity) {
        FreightAllocation a = new FreightAllocation();
        a.setShopId(SHOP_ID);
        a.setShipmentId(shipmentId);
        a.setAsin("B0TEST");
        a.setSku("SKU-1");
        a.setQuantity(quantity);
        a.setFreightCost(freight);
        a.setDutyCost(duty);
        a.setInsuranceCost(BigDecimal.ZERO);
        a.setOtherCost(BigDecimal.ZERO);
        a.setTotalCost(freight.add(duty));
        a.setAllocationMethod("WEIGHT");
        return a;
    }

    private FbaReceiptDiscrepancy discrepancy(Long shipmentId, int expected, int received, String status) {
        FbaReceiptDiscrepancy d = new FbaReceiptDiscrepancy();
        d.setId(shipmentId);
        d.setShopId(SHOP_ID);
        d.setShipmentId(shipmentId);
        d.setAsin("B0TEST");
        d.setSku("SKU-1");
        d.setExpectedQuantity(expected);
        d.setReceivedQuantity(received);
        d.setDifference(received - expected);
        d.setDiscrepancyType(received - expected < 0 ? "UNDERRECEIVED" : received - expected > 0 ? "OVERRECEIVED" : "MATCHED");
        d.setStatus(status);
        d.setCreateTime(LocalDateTime.now());
        return d;
    }
}
