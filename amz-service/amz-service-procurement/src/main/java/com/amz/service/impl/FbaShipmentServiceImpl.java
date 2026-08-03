package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.FbaShipmentItemMapper;
import com.amz.mapper.FbaShipmentMapper;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
import com.amz.model.InventoryBatch;
import com.amz.service.FbaShipmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * FBA 货件管理服务实现。
 * <p>
 * 覆盖 Send to Amazon 全流程 + 头程费用分摊 + FIFO 批次管理。
 */
@Slf4j
@Service
public class FbaShipmentServiceImpl implements FbaShipmentService {

    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private FbaShipmentMapper fbaShipmentMapper;

    @Autowired
    private FbaShipmentItemMapper fbaShipmentItemMapper;

    @Autowired
    private InventoryBatchMapper inventoryBatchMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FbaShipment createShipment(FbaShipment shipment) {
        if (shipment.getShopId() == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        shipment.setShipmentNo("FBA" + System.currentTimeMillis());
        if (shipment.getStatus() == null) {
            shipment.setStatus("CREATED");
        }
        if (shipment.getBoxCount() == null) shipment.setBoxCount(0);
        if (shipment.getTotalWeight() == null) shipment.setTotalWeight(BigDecimal.ZERO);
        if (shipment.getTotalVolume() == null) shipment.setTotalVolume(BigDecimal.ZERO);
        if (shipment.getFreightCost() == null) shipment.setFreightCost(BigDecimal.ZERO);
        if (shipment.getCustomsCost() == null) shipment.setCustomsCost(BigDecimal.ZERO);
        if (shipment.getTaxCost() == null) shipment.setTaxCost(BigDecimal.ZERO);
        if (shipment.getOtherCost() == null) shipment.setOtherCost(BigDecimal.ZERO);
        if (shipment.getTotalCost() == null) shipment.setTotalCost(BigDecimal.ZERO);
        fbaShipmentMapper.insert(shipment);
        return shipment;
    }

    @Override
    public FbaShipment updateShipment(FbaShipment shipment) {
        if (shipment.getId() == null) {
            throw new AttrIsNullException("货件ID不能为空");
        }
        fbaShipmentMapper.updateById(shipment);
        return shipment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FbaShipmentItem addShipmentItem(FbaShipmentItem item) {
        if (item.getFbaShipmentId() == null || item.getSku() == null || item.getQuantity() == null) {
            throw new AttrIsNullException("货件ID、SKU和数量不能为空");
        }
        if (item.getReceivedQuantity() == null) item.setReceivedQuantity(0);
        if (item.getFreightAllocation() == null) item.setFreightAllocation(BigDecimal.ZERO);
        if (item.getCustomsAllocation() == null) item.setCustomsAllocation(BigDecimal.ZERO);
        if (item.getTotalCost() == null) item.setTotalCost(BigDecimal.ZERO);
        fbaShipmentItemMapper.insert(item);
        return item;
    }

    @Override
    public List<FbaShipmentItem> listShipmentItems(Long shipmentId) {
        LambdaQueryWrapper<FbaShipmentItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FbaShipmentItem::getFbaShipmentId, shipmentId);
        return fbaShipmentItemMapper.selectList(wrapper);
    }

    @Override
    public List<FbaShipment> listShipments(Long shopId, String status) {
        LambdaQueryWrapper<FbaShipment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FbaShipment::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(FbaShipment::getStatus, status);
        }
        wrapper.orderByDesc(FbaShipment::getId);
        return fbaShipmentMapper.selectList(wrapper);
    }

    @Override
    public FbaShipment getShipment(Long id) {
        FbaShipment shipment = fbaShipmentMapper.selectById(id);
        if (shipment == null) {
            throw new AttrIsNullException("货件不存在：id=" + id);
        }
        return shipment;
    }

    @Override
    public FbaShipment updateShipmentStatus(Long id, String status) {
        FbaShipment shipment = getShipment(id);
        shipment.setStatus(status);
        if ("DELIVERED".equals(status) || "CLOSED".equals(status)) {
            shipment.setActualArrival(LocalDate.now());
        }
        fbaShipmentMapper.updateById(shipment);
        return shipment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FbaShipment confirmShipment(Long id, String carrier, String trackingNo) {
        FbaShipment shipment = getShipment(id);
        if (!"READY_TO_SHIP".equals(shipment.getStatus()) && !"CREATED".equals(shipment.getStatus())) {
            throw new IllegalStateException("仅 READY_TO_SHIP/CREATED 状态可确认发货，当前状态：" + shipment.getStatus());
        }
        shipment.setCarrier(carrier);
        shipment.setMasterTrackingNo(trackingNo);
        shipment.setStatus("SHIPPED");
        fbaShipmentMapper.updateById(shipment);
        log.info("FBA货件已发货：shipmentNo={}, carrier={}, trackingNo={}", shipment.getShipmentNo(), carrier, trackingNo);
        return shipment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> allocateCosts(Long shipmentId) {
        FbaShipment shipment = getShipment(shipmentId);
        List<FbaShipmentItem> items = listShipmentItems(shipmentId);

        if (items.isEmpty()) {
            throw new IllegalStateException("货件无明细，无法分摊费用");
        }

        // 计算总数量用于按比例分摊
        int totalQty = items.stream().mapToInt(FbaShipmentItem::getQuantity).sum();
        if (totalQty <= 0) {
            throw new IllegalStateException("货件总数量为0，无法分摊费用");
        }

        BigDecimal totalFreight = shipment.getFreightCost() != null ? shipment.getFreightCost() : BigDecimal.ZERO;
        BigDecimal totalCustoms = shipment.getCustomsCost() != null ? shipment.getCustomsCost() : BigDecimal.ZERO;
        BigDecimal totalTax = shipment.getTaxCost() != null ? shipment.getTaxCost() : BigDecimal.ZERO;
        BigDecimal totalOther = shipment.getOtherCost() != null ? shipment.getOtherCost() : BigDecimal.ZERO;
        BigDecimal totalCost = totalFreight.add(totalCustoms).add(totalTax).add(totalOther);

        List<Map<String, Object>> allocationDetails = new ArrayList<>();

        for (FbaShipmentItem item : items) {
            BigDecimal ratio = BigDecimal.valueOf(item.getQuantity())
                    .divide(BigDecimal.valueOf(totalQty), 6, RoundingMode.HALF_UP);

            BigDecimal freightAlloc = totalFreight.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            BigDecimal customsAlloc = totalCustoms.add(totalTax).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            BigDecimal otherAlloc = totalOther.multiply(ratio).setScale(2, RoundingMode.HALF_UP);

            BigDecimal itemTotalCost = item.getUnitCost() != null
                    ? item.getUnitCost().multiply(BigDecimal.valueOf(item.getQuantity()))
                    : BigDecimal.ZERO;
            itemTotalCost = itemTotalCost.add(freightAlloc).add(customsAlloc).add(otherAlloc);

            item.setFreightAllocation(freightAlloc);
            item.setCustomsAllocation(customsAlloc);
            item.setTotalCost(itemTotalCost);
            fbaShipmentItemMapper.updateById(item);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sku", item.getSku());
            detail.put("asin", item.getAsin());
            detail.put("quantity", item.getQuantity());
            detail.put("freightAllocation", freightAlloc);
            detail.put("customsAllocation", customsAlloc);
            detail.put("otherAllocation", otherAlloc);
            detail.put("totalCost", itemTotalCost);
            detail.put("unitCost", itemTotalCost.divide(BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_UP));
            allocationDetails.add(detail);
        }

        // 更新货件总成本
        shipment.setTotalCost(totalCost);
        fbaShipmentMapper.updateById(shipment);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipmentId", shipmentId);
        result.put("shipmentNo", shipment.getShipmentNo());
        result.put("totalQuantity", totalQty);
        result.put("totalFreight", totalFreight);
        result.put("totalCustoms", totalCustoms.add(totalTax));
        result.put("totalOther", totalOther);
        result.put("totalCost", totalCost);
        result.put("allocationDetails", allocationDetails);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> processReceipt(Long shipmentId, List<Map<String, Object>> receivedItems) {
        FbaShipment shipment = getShipment(shipmentId);
        List<FbaShipmentItem> items = listShipmentItems(shipmentId);

        List<Map<String, Object>> results = new ArrayList<>();
        boolean allReceived = true;
        boolean hasDiscrepancy = false;

        for (Map<String, Object> received : receivedItems) {
            Long itemId = Long.valueOf(received.get("itemId").toString());
            Integer receivedQty = Integer.valueOf(received.get("receivedQty").toString());

            FbaShipmentItem item = items.stream()
                    .filter(i -> i.getId().equals(itemId))
                    .findFirst()
                    .orElseThrow(() -> new AttrIsNullException("货件明细不存在：itemId=" + itemId));

            int discrepancy = receivedQty - item.getQuantity();
            item.setReceivedQuantity(receivedQty);
            fbaShipmentItemMapper.updateById(item);

            // 创建库存批次
            if (receivedQty > 0) {
                receiveBatch(shipmentId, itemId, receivedQty);
            }

            Map<String, Object> itemResult = new LinkedHashMap<>();
            itemResult.put("sku", item.getSku());
            itemResult.put("expectedQty", item.getQuantity());
            itemResult.put("receivedQty", receivedQty);
            itemResult.put("discrepancy", discrepancy);
            if (discrepancy != 0) {
                hasDiscrepancy = true;
                itemResult.put("status", discrepancy > 0 ? "OVER_RECEIVING" : "SHORT_RECEIVING");
            } else {
                itemResult.put("status", "MATCHED");
            }
            results.add(itemResult);

            if (receivedQty < item.getQuantity()) {
                allReceived = false;
            }
        }

        // 更新货件状态
        if (allReceived) {
            shipment.setStatus("CLOSED");
        } else {
            shipment.setStatus("RECEIVING");
        }
        shipment.setActualArrival(LocalDate.now());
        fbaShipmentMapper.updateById(shipment);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipmentId", shipmentId);
        result.put("shipmentNo", shipment.getShipmentNo());
        result.put("hasDiscrepancy", hasDiscrepancy);
        result.put("allReceived", allReceived);
        result.put("itemResults", results);

        if (hasDiscrepancy) {
            log.warn("FBA货件存在签收差异：shipmentNo={}, 详情={}", shipment.getShipmentNo(), results);
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryBatch receiveBatch(Long shipmentId, Long shipmentItemId, Integer receivedQty) {
        FbaShipment shipment = getShipment(shipmentId);
        FbaShipmentItem item = fbaShipmentItemMapper.selectById(shipmentItemId);
        if (item == null) {
            throw new AttrIsNullException("货件明细不存在：itemId=" + shipmentItemId);
        }

        // 计算批次单位成本（采购成本 + 分摊的头程费用）
        BigDecimal unitCost = item.getUnitCost() != null ? item.getUnitCost() : BigDecimal.ZERO;
        if (item.getFreightAllocation() != null) {
            unitCost = unitCost.add(item.getFreightAllocation().divide(BigDecimal.valueOf(item.getQuantity()), 4, RoundingMode.HALF_UP));
        }
        if (item.getCustomsAllocation() != null) {
            unitCost = unitCost.add(item.getCustomsAllocation().divide(BigDecimal.valueOf(item.getQuantity()), 4, RoundingMode.HALF_UP));
        }

        BigDecimal totalCost = unitCost.multiply(BigDecimal.valueOf(receivedQty)).setScale(2, RoundingMode.HALF_UP);

        InventoryBatch batch = new InventoryBatch();
        batch.setShopId(shipment.getShopId());
        batch.setBatchNo("BAT" + LocalDate.now().format(BATCH_FMT) + String.format("%04d", System.currentTimeMillis() % 10000));
        batch.setPurchaseOrderId(null);
        batch.setInboundOrderId(shipmentId);
        batch.setSku(item.getSku());
        batch.setAsin(item.getAsin());
        batch.setWarehouseId(shipment.getWarehouseId());
        batch.setQuantity(receivedQty);
        batch.setAvailableQuantity(receivedQty);
        batch.setUnitCost(unitCost.setScale(2, RoundingMode.HALF_UP));
        batch.setFreightCost(item.getFreightAllocation());
        batch.setCustomsCost(item.getCustomsAllocation());
        batch.setOtherCost(BigDecimal.ZERO);
        batch.setTotalCost(totalCost);
        batch.setInboundDate(LocalDate.now());
        batch.setStatus("ACTIVE");
        inventoryBatchMapper.insert(batch);

        log.info("批次入库完成：batchNo={}, sku={}, qty={}, unitCost={}", batch.getBatchNo(), batch.getSku(), receivedQty, batch.getUnitCost());
        return batch;
    }

    @Override
    public List<InventoryBatch> listBatchesBySku(Long shopId, String sku) {
        LambdaQueryWrapper<InventoryBatch> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryBatch::getShopId, shopId)
               .eq(InventoryBatch::getSku, sku)
               .eq(InventoryBatch::getStatus, "ACTIVE")
               .orderByAsc(InventoryBatch::getInboundDate);  // FIFO：按入库日期升序
        return inventoryBatchMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> fifoOutbound(Long shopId, String sku, Integer quantity) {
        List<InventoryBatch> batches = listBatchesBySku(shopId, sku);
        List<Map<String, Object>> outboundDetails = new ArrayList<>();
        int remaining = quantity;

        for (InventoryBatch batch : batches) {
            if (remaining <= 0) break;
            if (batch.getAvailableQuantity() <= 0) continue;

            int deductQty = Math.min(batch.getAvailableQuantity(), remaining);
            batch.setAvailableQuantity(batch.getAvailableQuantity() - deductQty);
            if (batch.getAvailableQuantity() == 0) {
                batch.setStatus("DEPLETED");
            }
            inventoryBatchMapper.updateById(batch);

            BigDecimal subtotal = batch.getUnitCost().multiply(BigDecimal.valueOf(deductQty));

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("batchNo", batch.getBatchNo());
            detail.put("sku", batch.getSku());
            detail.put("quantity", deductQty);
            detail.put("unitCost", batch.getUnitCost());
            detail.put("subtotal", subtotal);
            detail.put("inboundDate", batch.getInboundDate());
            outboundDetails.add(detail);

            remaining -= deductQty;
        }

        if (remaining > 0) {
            log.warn("FIFO出库库存不足：shopId={}, sku={}, 请求={}, 缺={}", shopId, sku, quantity, remaining);
        }

        return outboundDetails;
    }
}
