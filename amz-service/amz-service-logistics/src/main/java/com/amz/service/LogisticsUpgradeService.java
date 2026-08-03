package com.amz.service;

import com.amz.model.CarrierQuote;
import com.amz.model.FbaReceiptDiscrepancy;
import com.amz.model.FreightAllocation;
import com.amz.model.InventoryTransfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物流升级服务接口。
 * <p>
 * 覆盖：物流商比价/库存调拨/头程费用分摊/签收差异处理
 */
public interface LogisticsUpgradeService {

    // ===== 物流商比价 =====

    /** 保存物流商报价 */
    CarrierQuote saveQuote(CarrierQuote quote);

    /** 查询物流商报价列表 */
    List<CarrierQuote> listQuotes(Long shopId, String serviceType);

    /** 运费比价（按路线查询所有承运商报价） */
    Map<String, Object> compareQuotes(Long shopId, String originPort, String destinationPort, BigDecimal weightKg, BigDecimal volumeCbm);

    // ===== 库存调拨 =====

    /** 创建库存调拨单 */
    InventoryTransfer createTransfer(InventoryTransfer transfer);

    /** 审批调拨单 */
    InventoryTransfer approveTransfer(Long transferId, boolean approved);

    /** 确认调拨发出 */
    InventoryTransfer shipTransfer(Long transferId, String carrier, String trackingNo);

    /** 确认调拨到货 */
    InventoryTransfer receiveTransfer(Long transferId);

    /** 查询调拨单列表 */
    List<InventoryTransfer> listTransfers(Long shopId, String status);

    // ===== 头程费用分摊 =====

    /** 批量保存头程费用分摊明细 */
    List<FreightAllocation> saveAllocations(List<FreightAllocation> allocations);

    /** 查询货件的头程费用分摊明细 */
    List<FreightAllocation> listAllocations(Long shipmentId);

    /** 按分摊方法计算头程费用 */
    Map<String, Object> calculateFreightAllocation(Long shipmentId, String method, BigDecimal totalFreight, BigDecimal totalDuty, BigDecimal totalInsurance);

    // ===== FBA 签收差异 =====

    /** 保存签收差异记录 */
    FbaReceiptDiscrepancy saveDiscrepancy(FbaReceiptDiscrepancy discrepancy);

    /** 查询签收差异列表 */
    List<FbaReceiptDiscrepancy> listDiscrepancies(Long shopId, String status);

    /** 处理签收差异 */
    FbaReceiptDiscrepancy resolveDiscrepancy(Long discrepancyId, String resolution);
}
