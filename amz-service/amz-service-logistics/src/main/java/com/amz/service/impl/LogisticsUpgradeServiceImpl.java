package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.*;
import com.amz.model.*;
import com.amz.service.LogisticsUpgradeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 物流升级服务实现。
 */
@Slf4j
@Service
public class LogisticsUpgradeServiceImpl implements LogisticsUpgradeService {

    @Autowired
    private CarrierQuoteMapper carrierQuoteMapper;

    @Autowired
    private InventoryTransferMapper inventoryTransferMapper;

    @Autowired
    private FreightAllocationMapper freightAllocationMapper;

    @Autowired
    private FbaReceiptDiscrepancyMapper fbaReceiptDiscrepancyMapper;

    // ==================== 物流商比价 ====================

    @Override
    public CarrierQuote saveQuote(CarrierQuote quote) {
        if (quote.getShopId() == null || quote.getCarrierName() == null) {
            throw new AttrIsNullException("店铺ID和承运商名称不能为空");
        }
        if (quote.getStatus() == null) quote.setStatus("ACTIVE");
        if (quote.getCurrency() == null) quote.setCurrency("USD");
        if (quote.getFuelSurchargeRate() == null) quote.setFuelSurchargeRate(BigDecimal.ZERO);
        carrierQuoteMapper.insert(quote);
        return quote;
    }

    @Override
    public List<CarrierQuote> listQuotes(Long shopId, String serviceType) {
        LambdaQueryWrapper<CarrierQuote> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CarrierQuote::getShopId, shopId)
               .eq(CarrierQuote::getStatus, "ACTIVE");
        if (serviceType != null && !serviceType.isBlank()) {
            wrapper.eq(CarrierQuote::getServiceType, serviceType);
        }
        return carrierQuoteMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> compareQuotes(Long shopId, String originPort, String destinationPort,
                                              BigDecimal weightKg, BigDecimal volumeCbm) {
        LambdaQueryWrapper<CarrierQuote> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CarrierQuote::getShopId, shopId)
               .eq(CarrierQuote::getStatus, "ACTIVE");
        if (originPort != null) wrapper.eq(CarrierQuote::getOriginPort, originPort);
        if (destinationPort != null) wrapper.eq(CarrierQuote::getDestinationPort, destinationPort);
        List<CarrierQuote> quotes = carrierQuoteMapper.selectList(wrapper);

        List<Map<String, Object>> comparisons = new ArrayList<>();
        for (CarrierQuote q : quotes) {
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("carrierName", q.getCarrierName());
            comparison.put("serviceType", q.getServiceType());
            comparison.put("transitDays", q.getTransitDays());

            // 计算总运费
            BigDecimal freightCost = BigDecimal.ZERO;
            if (weightKg != null && q.getPricePerKg() != null && q.getPricePerKg().compareTo(BigDecimal.ZERO) > 0) {
                freightCost = q.getPricePerKg().multiply(weightKg);
            } else if (volumeCbm != null && q.getPricePerCbm() != null && q.getPricePerCbm().compareTo(BigDecimal.ZERO) > 0) {
                freightCost = q.getPricePerCbm().multiply(volumeCbm);
            }
            // 最低收费
            if (q.getMinCharge() != null && freightCost.compareTo(q.getMinCharge()) < 0) {
                freightCost = q.getMinCharge();
            }
            // 燃油附加费
            BigDecimal fuelSurcharge = BigDecimal.ZERO;
            if (q.getFuelSurchargeRate() != null && q.getFuelSurchargeRate().compareTo(BigDecimal.ZERO) > 0) {
                fuelSurcharge = freightCost.multiply(q.getFuelSurchargeRate()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            }
            BigDecimal totalCost = freightCost.add(fuelSurcharge);

            comparison.put("freightCost", freightCost);
            comparison.put("fuelSurcharge", fuelSurcharge);
            comparison.put("totalCost", totalCost);
            comparisons.add(comparison);
        }

        // 按总运费升序排序
        comparisons.sort((a, b) -> ((BigDecimal) a.get("totalCost")).compareTo((BigDecimal) b.get("totalCost")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("originPort", originPort);
        result.put("destinationPort", destinationPort);
        result.put("weightKg", weightKg);
        result.put("volumeCbm", volumeCbm);
        result.put("quotes", comparisons);
        if (!comparisons.isEmpty()) {
            result.put("recommended", comparisons.get(0).get("carrierName"));
        }
        return result;
    }

    // ==================== 库存调拨 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryTransfer createTransfer(InventoryTransfer transfer) {
        if (transfer.getShopId() == null || transfer.getAsin() == null || transfer.getQuantity() == null) {
            throw new AttrIsNullException("店铺ID、ASIN和调拨数量不能为空");
        }
        transfer.setTransferNo("TRF" + System.currentTimeMillis());
        if (transfer.getStatus() == null) transfer.setStatus("DRAFT");
        if (transfer.getShippingCost() == null) transfer.setShippingCost(BigDecimal.ZERO);
        inventoryTransferMapper.insert(transfer);
        return transfer;
    }

    @Override
    public InventoryTransfer approveTransfer(Long transferId, boolean approved) {
        InventoryTransfer transfer = inventoryTransferMapper.selectById(transferId);
        if (transfer == null) throw new AttrIsNullException("调拨单不存在：id=" + transferId);
        transfer.setStatus(approved ? "APPROVED" : "CANCELLED");
        inventoryTransferMapper.updateById(transfer);
        return transfer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryTransfer shipTransfer(Long transferId, String carrier, String trackingNo) {
        InventoryTransfer transfer = inventoryTransferMapper.selectById(transferId);
        if (transfer == null) throw new AttrIsNullException("调拨单不存在：id=" + transferId);
        transfer.setStatus("IN_TRANSIT");
        transfer.setCarrier(carrier);
        transfer.setTrackingNo(trackingNo);
        inventoryTransferMapper.updateById(transfer);
        return transfer;
    }

    @Override
    public InventoryTransfer receiveTransfer(Long transferId) {
        InventoryTransfer transfer = inventoryTransferMapper.selectById(transferId);
        if (transfer == null) throw new AttrIsNullException("调拨单不存在：id=" + transferId);
        transfer.setStatus("RECEIVED");
        inventoryTransferMapper.updateById(transfer);
        return transfer;
    }

    @Override
    public List<InventoryTransfer> listTransfers(Long shopId, String status) {
        LambdaQueryWrapper<InventoryTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryTransfer::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(InventoryTransfer::getStatus, status);
        }
        wrapper.orderByDesc(InventoryTransfer::getId);
        return inventoryTransferMapper.selectList(wrapper);
    }

    // ==================== 头程费用分摊 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<FreightAllocation> saveAllocations(List<FreightAllocation> allocations) {
        for (FreightAllocation a : allocations) {
            a.setTotalCost((a.getFreightCost() != null ? a.getFreightCost() : BigDecimal.ZERO)
                    .add(a.getDutyCost() != null ? a.getDutyCost() : BigDecimal.ZERO)
                    .add(a.getInsuranceCost() != null ? a.getInsuranceCost() : BigDecimal.ZERO)
                    .add(a.getOtherCost() != null ? a.getOtherCost() : BigDecimal.ZERO));
            if (a.getQuantity() != null && a.getQuantity() > 0) {
                a.setUnitCost(a.getTotalCost().divide(BigDecimal.valueOf(a.getQuantity()), 2, RoundingMode.HALF_UP));
            }
            freightAllocationMapper.insert(a);
        }
        return allocations;
    }

    @Override
    public List<FreightAllocation> listAllocations(Long shipmentId) {
        LambdaQueryWrapper<FreightAllocation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FreightAllocation::getShipmentId, shipmentId);
        return freightAllocationMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> calculateFreightAllocation(Long shipmentId, String method,
                                                            BigDecimal totalFreight, BigDecimal totalDuty, BigDecimal totalInsurance) {
        LambdaQueryWrapper<FreightAllocation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FreightAllocation::getShipmentId, shipmentId);
        List<FreightAllocation> allocations = freightAllocationMapper.selectList(wrapper);

        if (allocations.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("shipmentId", shipmentId);
            empty.put("error", "未找到分摊明细记录");
            return empty;
        }

        // 计算分摊基准总量
        BigDecimal totalBase;
        switch (method != null ? method : "WEIGHT") {
            case "VOLUME":
                totalBase = allocations.stream().map(FreightAllocation::getVolumeCbm)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                break;
            case "QUANTITY":
                totalBase = BigDecimal.valueOf(allocations.stream().mapToInt(a -> a.getQuantity() != null ? a.getQuantity() : 0).sum());
                break;
            default: // WEIGHT
                totalBase = allocations.stream().map(FreightAllocation::getWeightKg)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        if (totalBase.compareTo(BigDecimal.ZERO) == 0) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "分摊基准总量为0，无法计算");
            return err;
        }

        // 按比例分摊
        for (FreightAllocation a : allocations) {
            BigDecimal base;
            switch (method != null ? method : "WEIGHT") {
                case "VOLUME": base = a.getVolumeCbm() != null ? a.getVolumeCbm() : BigDecimal.ZERO; break;
                case "QUANTITY": base = BigDecimal.valueOf(a.getQuantity() != null ? a.getQuantity() : 0); break;
                default: base = a.getWeightKg() != null ? a.getWeightKg() : BigDecimal.ZERO;
            }
            BigDecimal ratio = base.divide(totalBase, 6, RoundingMode.HALF_UP);
            a.setFreightCost(totalFreight != null ? totalFreight.multiply(ratio).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            a.setDutyCost(totalDuty != null ? totalDuty.multiply(ratio).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            a.setInsuranceCost(totalInsurance != null ? totalInsurance.multiply(ratio).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            a.setTotalCost(a.getFreightCost().add(a.getDutyCost()).add(a.getInsuranceCost())
                    .add(a.getOtherCost() != null ? a.getOtherCost() : BigDecimal.ZERO));
            if (a.getQuantity() != null && a.getQuantity() > 0) {
                a.setUnitCost(a.getTotalCost().divide(BigDecimal.valueOf(a.getQuantity()), 2, RoundingMode.HALF_UP));
            }
            a.setAllocationMethod(method);
            freightAllocationMapper.updateById(a);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipmentId", shipmentId);
        result.put("method", method);
        result.put("totalFreight", totalFreight);
        result.put("totalDuty", totalDuty);
        result.put("totalInsurance", totalInsurance);
        result.put("allocations", allocations);
        return result;
    }

    // ==================== FBA 签收差异 ====================

    @Override
    public FbaReceiptDiscrepancy saveDiscrepancy(FbaReceiptDiscrepancy discrepancy) {
        if (discrepancy.getShipmentId() == null || discrepancy.getAsin() == null) {
            throw new AttrIsNullException("货件ID和ASIN不能为空");
        }
        // 自动判定差异类型
        int diff = discrepancy.getDifference();
        if (diff > 0) {
            discrepancy.setDiscrepancyType("OVERRECEIVED");
        } else if (diff < 0) {
            discrepancy.setDiscrepancyType("UNDERRECEIVED");
        }
        if (discrepancy.getStatus() == null) discrepancy.setStatus("PENDING");
        fbaReceiptDiscrepancyMapper.insert(discrepancy);
        return discrepancy;
    }

    @Override
    public List<FbaReceiptDiscrepancy> listDiscrepancies(Long shopId, String status) {
        LambdaQueryWrapper<FbaReceiptDiscrepancy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FbaReceiptDiscrepancy::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(FbaReceiptDiscrepancy::getStatus, status);
        }
        wrapper.orderByDesc(FbaReceiptDiscrepancy::getId);
        return fbaReceiptDiscrepancyMapper.selectList(wrapper);
    }

    @Override
    public FbaReceiptDiscrepancy resolveDiscrepancy(Long discrepancyId, String resolution) {
        FbaReceiptDiscrepancy d = fbaReceiptDiscrepancyMapper.selectById(discrepancyId);
        if (d == null) throw new AttrIsNullException("差异记录不存在：id=" + discrepancyId);
        d.setStatus("RESOLVED");
        d.setResolution(resolution);
        fbaReceiptDiscrepancyMapper.updateById(d);
        return d;
    }
}
