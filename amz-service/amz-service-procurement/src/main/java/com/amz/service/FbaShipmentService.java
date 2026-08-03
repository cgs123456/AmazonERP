package com.amz.service;

import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
import com.amz.model.InventoryBatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * FBA 货件管理服务接口。
 * <p>
 * 覆盖 Send to Amazon 全流程：
 * 创建货件 → 装箱确认 → 发货 → 物流追踪 → 到港签收 → 成本分摊 → 批次入库
 */
public interface FbaShipmentService {

    /** 创建 FBA 货件 */
    FbaShipment createShipment(FbaShipment shipment);

    /** 更新货件信息 */
    FbaShipment updateShipment(FbaShipment shipment);

    /** 添加货件明细 */
    FbaShipmentItem addShipmentItem(FbaShipmentItem item);

    /** 查询货件明细列表 */
    List<FbaShipmentItem> listShipmentItems(Long shipmentId);

    /** 查询店铺货件列表 */
    List<FbaShipment> listShipments(Long shopId, String status);

    /** 获取货件详情 */
    FbaShipment getShipment(Long id);

    /** 更新货件状态 */
    FbaShipment updateShipmentStatus(Long id, String status);

    /** 确认发货（READY_TO_SHIP → SHIPPED） */
    FbaShipment confirmShipment(Long id, String carrier, String trackingNo);

    /**
     * 头程费用分摊：将运费/报关/税费按 SKU 数量比例分摊到明细。
     * 分摊后自动创建库存批次（status=ACTIVE，待到货后更新入库日期）。
     */
    Map<String, Object> allocateCosts(Long shipmentId);

    /**
     * FBA 签收处理：同步亚马逊签收数量，处理多签收/少签收异常。
     * 签收后更新对应库存批次可用数量。
     */
    Map<String, Object> processReceipt(Long shipmentId, List<Map<String, Object>> receivedItems);

    /**
     * 批次入库：到货后将批次状态设为 ACTIVE 并设置入库日期。
     * 触发先进先出成本计算更新。
     */
    InventoryBatch receiveBatch(Long shipmentId, Long shipmentItemId, Integer receivedQty);

    /** 查询 SKU 的库存批次列表（按入库日期升序，FIFO） */
    List<InventoryBatch> listBatchesBySku(Long shopId, String sku);

    /**
     * FIFO 出库：按先进先出原则扣减批次可用数量，返回出库成本。
     * @return 出库明细（批次号/数量/单位成本/小计）
     */
    List<Map<String, Object>> fifoOutbound(Long shopId, String sku, Integer quantity);
}
