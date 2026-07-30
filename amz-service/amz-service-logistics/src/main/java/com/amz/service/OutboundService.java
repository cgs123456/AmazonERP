package com.amz.service;

import com.amz.model.OutboundOrder;
import com.amz.model.WarehouseInventory;

import java.util.List;

/**
 * 出库流程服务接口。
 * <p>
 * 状态机：PENDING → PICKING → PACKED → SHIPPED（支持 CANCELLED）
 */
public interface OutboundService {

    /**
     * 创建出库单（状态置为 PENDING）。
     */
    OutboundOrder createOutboundOrder(OutboundOrder order);

    /**
     * 查询店铺出库单列表。
     */
    List<OutboundOrder> listOutboundOrders(Long shopId, String status);

    /**
     * 开始拣货（PENDING → PICKING）。
     */
    OutboundOrder pickOutbound(Long id);

    /**
     * 打包完成（PICKING → PACKED）。
     */
    OutboundOrder packOutbound(Long id);

    /**
     * 发货 + 库存扣减（PACKED → SHIPPED）。
     *
     * @param id          出库单 ID
     * @param carrier     承运商
     * @param trackingNo  追踪号
     * @param items       本次发货明细（sku/quantity），用于扣减库存
     */
    OutboundOrder shipOutbound(Long id, String carrier, String trackingNo, List<WarehouseInventory> items);

    /**
     * 取消出库单（仅 PENDING / PICKING 状态可取消）。
     */
    OutboundOrder cancelOutbound(Long id);
}
