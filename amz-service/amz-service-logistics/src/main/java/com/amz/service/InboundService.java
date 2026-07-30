package com.amz.service;

import com.amz.model.InboundOrder;
import com.amz.model.WarehouseInventory;

import java.util.List;

/**
 * 入库流程服务接口。
 * <p>
 * 状态机：PENDING → IN_TRANSIT → RECEIVED（支持 PARTIAL / CANCELLED）
 */
public interface InboundService {

    /**
     * 创建入库单（状态置为 PENDING）。
     */
    InboundOrder createInboundOrder(InboundOrder order);

    /**
     * 查询店铺入库单列表。
     */
    List<InboundOrder> listInboundOrders(Long shopId, String status);

    /**
     * 入库单状态流转：PENDING → IN_TRANSIT。
     */
    InboundOrder transitInbound(Long id);

    /**
     * 到货验收 + 库存增加（IN_TRANSIT → RECEIVED）。
     *
     * @param id    入库单 ID
     * @param items 本次收货明细（sku/quantity/batchNo/locationCode）
     */
    InboundOrder receiveInbound(Long id, List<WarehouseInventory> items);

    /**
     * 取消入库单（仅 PENDING / PARTIAL 状态可取消）。
     */
    InboundOrder cancelInbound(Long id);
}
