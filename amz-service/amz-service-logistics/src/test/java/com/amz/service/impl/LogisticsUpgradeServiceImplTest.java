package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 物流升级服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 覆盖四子域中被修复的缺陷，重点不在 CRUD 是否跑通，而在<b>容易被静默写坏的口径</b>：
 * <ul>
 *   <li><b>越权</b>：按 id 定位的单据操作必须校验店铺归属（{@code @ShopScoped} 覆盖不到）；</li>
 *   <li><b>状态机</b>：调拨单只能沿既定路径前进，且重复操作要幂等；</li>
 *   <li><b>比价</b>：重量价与体积价取高、过期报价排除、跨币种不给单一结论；</li>
 *   <li><b>金额守恒</b>：分摊后各行之和必须等于总额（尾差要有人吸收）；</li>
 *   <li><b>空值</b>：签收差异不再因为 difference 缺省而拆箱 NPE。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("物流升级服务单元测试")
class LogisticsUpgradeServiceImplTest {

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
    private LogisticsUpgradeServiceImpl service;

    @BeforeEach
    void setUpUser() {
        // 注入授权店铺列表，让归属校验生效（无 shops 时会按内部调用放行）
        UserContext.setUserId(9);
        UserContext.setShops(List.of(SHOP_ID));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    // ================================================================ 报价

    @Test
    @DisplayName("保存报价：请求体 shopId 不属于当前账号时拒绝，防止往他店写报价")
    void saveQuoteRejectsForeignShop() {
        CarrierQuote quote = quote(SHOP_ID + 100, "COSCO", "SEA");

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.saveQuote(quote));
        assertTrue(e.getMessage().contains("不属于当前账号可操作的店铺"), e.getMessage());
        verify(carrierQuoteMapper, never()).insert(any(CarrierQuote.class));
    }

    @Test
    @DisplayName("保存报价：缺运输方式时提前拦下，而不是撞数据库 NOT NULL 约束")
    void saveQuoteRequiresServiceType() {
        CarrierQuote quote = quote(SHOP_ID, "COSCO", null);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.saveQuote(quote));
        assertTrue(e.getMessage().contains("运输方式"), e.getMessage());
        verify(carrierQuoteMapper, never()).insert(any(CarrierQuote.class));
    }

    @Test
    @DisplayName("保存报价：两个单价都为空时拒绝，否则比价时会产出算不出金额的空行")
    void saveQuoteRequiresAtLeastOnePrice() {
        CarrierQuote quote = quote(SHOP_ID, "COSCO", "SEA");
        quote.setPricePerKg(null);
        quote.setPricePerCbm(null);

        assertThrows(CodeErrorException.class, () -> service.saveQuote(quote));
        verify(carrierQuoteMapper, never()).insert(any(CarrierQuote.class));
    }

    @Test
    @DisplayName("比价：同时给出重量与体积时按两者取高计费（旧实现只按重量，会低估运费）")
    void compareQuotesUsesHigherOfWeightAndVolume() {
        // 重量算 100，体积算 300 —— 正确结果应当是 300
        CarrierQuote q = quote(SHOP_ID, "COSCO", "SEA");
        q.setPricePerKg(new BigDecimal("10.00"));
        q.setPricePerCbm(new BigDecimal("100.00"));
        q.setMinCharge(BigDecimal.ZERO);
        q.setFuelSurchargeRate(BigDecimal.ZERO);
        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of(q));

        Map<String, Object> result = service.compareQuotes(SHOP_ID, "Shenzhen", "LAX",
                new BigDecimal("10"), new BigDecimal("3"));

        List<?> quotes = (List<?>) result.get("quotes");
        assertEquals(1, quotes.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) quotes.get(0);
        assertEquals("VOLUME", first.get("chargeableBasis"));
        assertEquals(new BigDecimal("300.00"), first.get("freightCost"));
        assertEquals(new BigDecimal("300.00"), first.get("totalCost"));
    }

    @Test
    @DisplayName("比价：排除已过失效日期的报价，并说明排除了几条")
    void compareQuotesExcludesExpired() {
        CarrierQuote valid = quote(SHOP_ID, "COSCO", "SEA");
        valid.setPricePerKg(new BigDecimal("3.00"));
        valid.setExpiryDate(LocalDate.now().plusDays(10));

        CarrierQuote expired = quote(SHOP_ID, "OldLine", "SEA");
        expired.setPricePerKg(new BigDecimal("1.00"));
        expired.setExpiryDate(LocalDate.now().minusDays(1));

        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of(valid, expired));

        Map<String, Object> result = service.compareQuotes(SHOP_ID, "Shenzhen", "LAX",
                new BigDecimal("10"), null);

        assertEquals(1, ((List<?>) result.get("quotes")).size(), "过期报价不应参与比价");
        assertEquals(1, result.get("excludedExpiredCount"));
        assertTrue(((List<?>) result.get("warnings")).toString().contains("失效日期"));
    }

    @Test
    @DisplayName("比价：多币种时不给全局推荐，改为按币种分别推荐")
    void compareQuotesDoesNotMixCurrencies() {
        CarrierQuote usd = quote(SHOP_ID, "COSCO", "SEA");
        usd.setPricePerKg(new BigDecimal("3.00"));
        usd.setCurrency("USD");

        CarrierQuote cny = quote(SHOP_ID, "SF Express", "SEA");
        cny.setPricePerKg(new BigDecimal("20.00"));
        cny.setCurrency("CNY");

        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of(usd, cny));

        Map<String, Object> result = service.compareQuotes(SHOP_ID, "Shenzhen", "LAX",
                new BigDecimal("10"), null);

        // 3 USD 与 20 CNY 不可直接比大小，给单一「最便宜」就是错结论
        assertEquals(null, result.get("recommended"));
        @SuppressWarnings("unchecked")
        Map<String, String> byCurrency = (Map<String, String>) result.get("recommendedByCurrency");
        assertEquals("COSCO", byCurrency.get("USD"));
        assertEquals("SF Express", byCurrency.get("CNY"));
    }

    @Test
    @DisplayName("比价：重量与体积都没给时直接报错，不返回一堆 0 元报价")
    void compareQuotesRequiresWeightOrVolume() {
        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.compareQuotes(SHOP_ID, "Shenzhen", "LAX", null, null));
        assertTrue(e.getMessage().contains("重量或体积"), e.getMessage());
    }

    @Test
    @DisplayName("标记过期报价：只更新已过失效日期且状态仍为 ACTIVE 的条目")
    void expireOutdatedQuotesOnlyTouchesActiveExpired() {
        CarrierQuote outdated = quote(SHOP_ID, "OldLine", "SEA");
        outdated.setId(11L);
        outdated.setExpiryDate(LocalDate.now().minusDays(2));
        when(carrierQuoteMapper.selectList(any())).thenReturn(List.of(outdated));

        int updated = service.expireOutdatedQuotes(SHOP_ID);

        assertEquals(1, updated);
        ArgumentCaptor<CarrierQuote> captor = ArgumentCaptor.forClass(CarrierQuote.class);
        verify(carrierQuoteMapper).updateById(captor.capture());
        assertEquals("EXPIRED", captor.getValue().getStatus());
    }

    // ================================================================ 调拨状态机

    @Test
    @DisplayName("审批：草稿可审批通过")
    void approveTransferFromDraft() {
        InventoryTransfer transfer = transfer(21L, "DRAFT");
        when(inventoryTransferMapper.selectById(21L)).thenReturn(transfer);

        InventoryTransfer result = service.approveTransfer(21L, true);

        assertEquals("APPROVED", result.getStatus());
        verify(inventoryTransferMapper).updateById(transfer);
    }

    @Test
    @DisplayName("审批：重复审批通过是幂等的，不报错也不再写库")
    void approveTransferIsIdempotent() {
        InventoryTransfer transfer = transfer(22L, "APPROVED");
        when(inventoryTransferMapper.selectById(22L)).thenReturn(transfer);

        InventoryTransfer result = service.approveTransfer(22L, true);

        assertEquals("APPROVED", result.getStatus());
        verify(inventoryTransferMapper, never()).updateById(any(InventoryTransfer.class));
    }

    @Test
    @DisplayName("审批：已取消的单不可再改判为通过")
    void approveTransferRejectsCancelled() {
        InventoryTransfer transfer = transfer(23L, "CANCELLED");
        when(inventoryTransferMapper.selectById(23L)).thenReturn(transfer);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.approveTransfer(23L, true));
        assertTrue(e.getMessage().contains("CANCELLED"), e.getMessage());
        verify(inventoryTransferMapper, never()).updateById(any(InventoryTransfer.class));
    }

    @Test
    @DisplayName("发出：未审批的草稿单不能直接发出（必须先审批）")
    void shipTransferRequiresApproved() {
        InventoryTransfer transfer = transfer(24L, "DRAFT");
        when(inventoryTransferMapper.selectById(24L)).thenReturn(transfer);

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.shipTransfer(24L, "SF", "SF123"));
        assertTrue(e.getMessage().contains("APPROVED"), e.getMessage());
        verify(inventoryTransferMapper, never()).updateById(any(InventoryTransfer.class));
    }

    @Test
    @DisplayName("发出：已审批 → 运输中，并落承运商与单号")
    void shipTransferFromApproved() {
        InventoryTransfer transfer = transfer(25L, "APPROVED");
        when(inventoryTransferMapper.selectById(25L)).thenReturn(transfer);

        InventoryTransfer result = service.shipTransfer(25L, "SF Express", "SF123456");

        assertEquals("IN_TRANSIT", result.getStatus());
        assertEquals("SF Express", result.getCarrier());
        assertEquals("SF123456", result.getTrackingNo());
    }

    @Test
    @DisplayName("收货：没发出的单不能收货，否则库存账会凭空多出来")
    void receiveTransferRequiresInTransit() {
        InventoryTransfer transfer = transfer(26L, "DRAFT");
        when(inventoryTransferMapper.selectById(26L)).thenReturn(transfer);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.receiveTransfer(26L));
        assertTrue(e.getMessage().contains("IN_TRANSIT"), e.getMessage());
        verify(inventoryTransferMapper, never()).updateById(any(InventoryTransfer.class));
    }

    @Test
    @DisplayName("越权：操作他店调拨单被拒绝，且提示不区分「不存在」与「无权」")
    void transferAccessDeniedForForeignShop() {
        InventoryTransfer transfer = transfer(27L, "DRAFT");
        transfer.setShopId(SHOP_ID + 100);
        when(inventoryTransferMapper.selectById(27L)).thenReturn(transfer);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.approveTransfer(27L, true));
        assertEquals("调拨单不存在或无权访问", e.getMessage());
        verify(inventoryTransferMapper, never()).updateById(any(InventoryTransfer.class));
    }

    // ================================================================ 头程分摊

    @Test
    @DisplayName("越权：按 shipmentId 读头程成本前必须校验货件归属")
    void listAllocationsRejectsForeignShipment() {
        Shipment foreign = new Shipment();
        foreign.setId(31L);
        foreign.setShopId(SHOP_ID + 100);
        when(shipmentMapper.selectById(31L)).thenReturn(foreign);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.listAllocations(31L));
        assertEquals("货件不存在或无权访问", e.getMessage());
    }

    @Test
    @DisplayName("分摊：方法名拼错时直接报错，而不是静默按重量算")
    void calculateFreightRejectsUnknownMethod() {
        Shipment shipment = new Shipment();
        shipment.setId(41L);
        shipment.setShopId(SHOP_ID);
        when(shipmentMapper.selectById(41L)).thenReturn(shipment);

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.calculateFreightAllocation(41L, "WEIGTH", new BigDecimal("100"), null, null));
        assertTrue(e.getMessage().contains("分摊方法非法"), e.getMessage());
        // 方法名校验在查询明细之前完成，拼错时不应再去读一次分摊明细
        verify(freightAllocationMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("分摊：尾差由基准最大的行吸收，各行之和严格等于费用总额")
    void calculateFreightKeepsTotalBalanced() {
        // 三行等权，1000 / 3 = 333.3333…，逐行四舍五入后必然差 1 分
        mockShipmentAndAllocations(
                List.of(allocation(1, "1"), allocation(1, "2"), allocation(1, "3")), 42L);

        Map<String, Object> result = service.calculateFreightAllocation(42L, "WEIGHT",
                new BigDecimal("1000"), new BigDecimal("100.50"), null);

        assertEquals(Boolean.TRUE, result.get("balanced"), "分摊之和必须等于总额");
        assertEquals(new BigDecimal("1000.00"), result.get("allocatedFreight"));
        assertEquals(new BigDecimal("100.50"), result.get("allocatedDuty"));

        @SuppressWarnings("unchecked")
        List<FreightAllocation> allocations = (List<FreightAllocation>) result.get("allocations");
        BigDecimal sum = BigDecimal.ZERO;
        for (FreightAllocation a : allocations) {
            sum = sum.add(a.getFreightCost());
        }
        assertEquals(0, sum.compareTo(new BigDecimal("1000.00")), "各行运费之和必须等于 1000.00，实际 " + sum);
    }

    @Test
    @DisplayName("分摊：基准为 0 时明确报错，不产出一堆 0 成本的明细")
    void calculateFreightRejectsZeroBase() {
        mockShipmentAndAllocations(
                List.of(allocation(0, "1"), allocation(0, "2")), 43L);

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.calculateFreightAllocation(43L, "WEIGHT", new BigDecimal("100"), null, null));
        assertTrue(e.getMessage().contains("基准总量为 0"), e.getMessage());
    }

    // ================================================================ 签收差异

    @Test
    @DisplayName("签收差异：只给应收实收、不传 difference 时不抛 NPE，差异由两个数量算出")
    void saveDiscrepancyComputesDifferenceWithoutClientInput() {
        FbaReceiptDiscrepancy d = discrepancy(SHOP_ID);
        d.setExpectedQuantity(100);
        d.setReceivedQuantity(96);
        d.setDifference(null);

        FbaReceiptDiscrepancy saved = service.saveDiscrepancy(d);

        assertEquals(-4, saved.getDifference());
        assertEquals("UNDERRECEIVED", saved.getDiscrepancyType());
        assertEquals("PENDING", saved.getStatus());
    }

    @Test
    @DisplayName("签收差异：调用方自报的 difference 被忽略，一律以实收 − 应收为准")
    void saveDiscrepancyIgnoresClientReportedDifference() {
        FbaReceiptDiscrepancy d = discrepancy(SHOP_ID);
        d.setExpectedQuantity(100);
        d.setReceivedQuantity(96);
        d.setDifference(999);

        FbaReceiptDiscrepancy saved = service.saveDiscrepancy(d);

        assertEquals(-4, saved.getDifference());
    }

    @Test
    @DisplayName("签收差异：缺实收数量时提前拦下，而不是撞数据库 NOT NULL 约束")
    void saveDiscrepancyRequiresBothQuantities() {
        FbaReceiptDiscrepancy d = discrepancy(SHOP_ID);
        d.setExpectedQuantity(100);
        d.setReceivedQuantity(null);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.saveDiscrepancy(d));
        assertTrue(e.getMessage().contains("实收数量"), e.getMessage());
        verify(fbaReceiptDiscrepancyMapper, never()).insert(any(FbaReceiptDiscrepancy.class));
    }

    @Test
    @DisplayName("签收差异：0 差异记为 MATCHED 并直接结案，对账结论也是有效留痕")
    void saveDiscrepancyMarksMatched() {
        FbaReceiptDiscrepancy d = discrepancy(SHOP_ID);
        d.setExpectedQuantity(50);
        d.setReceivedQuantity(50);

        FbaReceiptDiscrepancy saved = service.saveDiscrepancy(d);

        assertEquals(0, saved.getDifference());
        assertEquals("MATCHED", saved.getDiscrepancyType());
        assertEquals("RESOLVED", saved.getStatus());
    }

    @Test
    @DisplayName("结案：处理结果为空时拒绝，避免留下没有下文的差异记录")
    void resolveDiscrepancyRequiresResolution() {
        FbaReceiptDiscrepancy d = discrepancy(SHOP_ID);
        d.setId(51L);
        d.setStatus("PENDING");
        when(fbaReceiptDiscrepancyMapper.selectById(51L)).thenReturn(d);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.resolveDiscrepancy(51L, "   "));
        assertTrue(e.getMessage().contains("处理结果"), e.getMessage());
        verify(fbaReceiptDiscrepancyMapper, never()).updateById(any(FbaReceiptDiscrepancy.class));
    }

    @Test
    @DisplayName("结案：已结案的再次结案是幂等的，不覆盖既有处理结果")
    void resolveDiscrepancyIsIdempotent() {
        FbaReceiptDiscrepancy d = discrepancy(SHOP_ID);
        d.setId(52L);
        d.setStatus("RESOLVED");
        d.setResolution("已提交索赔");
        when(fbaReceiptDiscrepancyMapper.selectById(52L)).thenReturn(d);

        FbaReceiptDiscrepancy result = service.resolveDiscrepancy(52L, "换个说法");

        assertEquals("已提交索赔", result.getResolution());
        verify(fbaReceiptDiscrepancyMapper, never()).updateById(any(FbaReceiptDiscrepancy.class));
    }

    @Test
    @DisplayName("核查：待处理可转入核查中，已结案的不再回退")
    void startInvestigatingTransitions() {
        FbaReceiptDiscrepancy pending = discrepancy(SHOP_ID);
        pending.setId(53L);
        pending.setStatus("PENDING");
        when(fbaReceiptDiscrepancyMapper.selectById(53L)).thenReturn(pending);

        assertEquals("INVESTIGATING", service.startInvestigating(53L).getStatus());

        FbaReceiptDiscrepancy resolved = discrepancy(SHOP_ID);
        resolved.setId(54L);
        resolved.setStatus("RESOLVED");
        when(fbaReceiptDiscrepancyMapper.selectById(54L)).thenReturn(resolved);

        assertEquals("RESOLVED", service.startInvestigating(54L).getStatus());
    }

    @Test
    @DisplayName("越权：结案他店差异记录被拒绝")
    void resolveDiscrepancyRejectsForeignShop() {
        FbaReceiptDiscrepancy d = discrepancy(SHOP_ID + 100);
        d.setId(55L);
        d.setStatus("PENDING");
        when(fbaReceiptDiscrepancyMapper.selectById(55L)).thenReturn(d);

        CodeErrorException e = assertThrows(CodeErrorException.class,
                () -> service.resolveDiscrepancy(55L, "结案"));
        assertEquals("差异记录不存在或无权访问", e.getMessage());
        verify(fbaReceiptDiscrepancyMapper, never()).updateById(any(FbaReceiptDiscrepancy.class));
    }

    @Test
    @DisplayName("新增调拨单：缺 SKU 时提前拦下（库中为 NOT NULL）")
    void createTransferRequiresSku() {
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.setShopId(SHOP_ID);
        transfer.setAsin("B0TEST");
        transfer.setQuantity(10);
        transfer.setFromWarehouseId(1L);
        transfer.setToWarehouseId(2L);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.createTransfer(transfer));
        assertTrue(e.getMessage().contains("SKU"), e.getMessage());
        verify(inventoryTransferMapper, never()).insert(any(InventoryTransfer.class));
    }

    @Test
    @DisplayName("新增调拨单：源仓与目标仓相同时拒绝")
    void createTransferRejectsSameWarehouse() {
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.setShopId(SHOP_ID);
        transfer.setAsin("B0TEST");
        transfer.setSku("SKU-1");
        transfer.setQuantity(10);
        transfer.setFromWarehouseId(3L);
        transfer.setToWarehouseId(3L);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.createTransfer(transfer));
        assertTrue(e.getMessage().contains("不能相同"), e.getMessage());
    }

    @Test
    @DisplayName("新增调拨单：正常创建时生成单号并落草稿")
    void createTransferSucceeds() {
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.setShopId(SHOP_ID);
        transfer.setAsin("B0TEST");
        transfer.setSku("SKU-1");
        transfer.setQuantity(10);
        transfer.setFromWarehouseId(1L);
        transfer.setToWarehouseId(2L);

        InventoryTransfer saved = service.createTransfer(transfer);

        assertNotNull(saved.getTransferNo());
        assertTrue(saved.getTransferNo().startsWith("TRF"));
        assertEquals("DRAFT", saved.getStatus());
        assertEquals(BigDecimal.ZERO, saved.getShippingCost());
        verify(inventoryTransferMapper, times(1)).insert(transfer);
    }

    // ================================================================ 构造工具

    private CarrierQuote quote(Long shopId, String carrier, String serviceType) {
        CarrierQuote q = new CarrierQuote();
        q.setShopId(shopId);
        q.setCarrierName(carrier);
        q.setServiceType(serviceType);
        q.setOriginPort("Shenzhen");
        q.setDestinationPort("LAX");
        q.setStatus("ACTIVE");
        q.setCurrency("USD");
        q.setPricePerKg(new BigDecimal("3.50"));
        return q;
    }

    private InventoryTransfer transfer(Long id, String status) {
        InventoryTransfer t = new InventoryTransfer();
        t.setId(id);
        t.setShopId(SHOP_ID);
        t.setTransferNo("TRF" + id);
        t.setFromWarehouseId(1L);
        t.setToWarehouseId(2L);
        t.setAsin("B0TEST");
        t.setSku("SKU-1");
        t.setQuantity(10);
        t.setStatus(status);
        t.setShippingCost(new BigDecimal("50.00"));
        return t;
    }

    private FreightAllocation allocation(double weight, String sku) {
        FreightAllocation a = new FreightAllocation();
        a.setShopId(SHOP_ID);
        a.setShipmentId(41L);
        a.setAsin("B0TEST");
        a.setSku("SKU-" + sku);
        a.setQuantity(100);
        a.setWeightKg(BigDecimal.valueOf(weight));
        a.setOtherCost(BigDecimal.ZERO);
        return a;
    }

    private FbaReceiptDiscrepancy discrepancy(Long shopId) {
        FbaReceiptDiscrepancy d = new FbaReceiptDiscrepancy();
        d.setShopId(shopId);
        d.setShipmentId(61L);
        d.setAsin("B0TEST");
        d.setSku("SKU-1");
        return d;
    }

    /**
     * 让分摊计算能跑到分摊逻辑：货件归属校验通过 + 返回给定的明细行。
     * <p>
     * {@code updateById} 不打桩（返回 0 也无所谓），本测试只关心写回内存对象的金额。
     */
    private void mockShipmentAndAllocations(List<FreightAllocation> rows, Long shipmentId) {
        Shipment shipment = new Shipment();
        shipment.setId(shipmentId);
        shipment.setShopId(SHOP_ID);
        when(shipmentMapper.selectById(shipmentId)).thenReturn(shipment);
        when(freightAllocationMapper.selectList(any())).thenReturn(new ArrayList<>(rows));
    }
}
