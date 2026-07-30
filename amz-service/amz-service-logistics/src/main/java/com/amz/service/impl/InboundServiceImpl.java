package com.amz.service.impl;

import com.amz.mapper.InboundOrderMapper;
import com.amz.model.InboundOrder;
import com.amz.model.WarehouseInventory;
import com.amz.service.InboundService;
import com.amz.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库流程服务实现。
 * 状态机：PENDING → IN_TRANSIT → RECEIVED / PARTIAL / CANCELLED
 */
@Service
public class InboundServiceImpl implements InboundService {

    @Autowired
    private InboundOrderMapper inboundOrderMapper;

    @Autowired
    private WarehouseService warehouseService;

    @Override
    public InboundOrder createInboundOrder(InboundOrder order) {
        order.setInboundNo("IN" + System.currentTimeMillis());
        if (order.getStatus() == null) {
            order.setStatus("PENDING");
        }
        if (order.getReceivedItems() == null) {
            order.setReceivedItems(0);
        }
        inboundOrderMapper.insert(order);
        return order;
    }

    @Override
    public List<InboundOrder> listInboundOrders(Long shopId, String status) {
        LambdaQueryWrapper<InboundOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InboundOrder::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(InboundOrder::getStatus, status);
        }
        wrapper.orderByDesc(InboundOrder::getId);
        return inboundOrderMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundOrder transitInbound(Long id) {
        InboundOrder order = mustExist(id);
        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("仅 PENDING 状态可流转到 IN_TRANSIT，当前=" + order.getStatus());
        }
        order.setStatus("IN_TRANSIT");
        inboundOrderMapper.updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundOrder receiveInbound(Long id, List<WarehouseInventory> items) {
        InboundOrder order = mustExist(id);
        if (!"IN_TRANSIT".equals(order.getStatus()) && !"PARTIAL".equals(order.getStatus())) {
            throw new IllegalStateException("仅 IN_TRANSIT / PARTIAL 状态可到货验收，当前=" + order.getStatus());
        }
        if (items != null) {
            int received = order.getReceivedItems() == null ? 0 : order.getReceivedItems();
            for (WarehouseInventory item : items) {
                if (item.getQuantity() == null || item.getQuantity() <= 0) {
                    continue;
                }
                warehouseService.increaseInventory(
                        order.getWarehouseId(),
                        order.getShopId(),
                        item.getSku(),
                        item.getQuantity(),
                        item.getBatchNo(),
                        item.getLocationCode());
                received += item.getQuantity();
            }
            order.setReceivedItems(received);
        }
        order.setActualArrival(LocalDateTime.now());
        // 全部到货 → RECEIVED；部分到货 → PARTIAL
        int total = order.getTotalItems() == null ? 0 : order.getTotalItems();
        if (total > 0 && order.getReceivedItems() >= total) {
            order.setStatus("RECEIVED");
        } else {
            order.setStatus("PARTIAL");
        }
        inboundOrderMapper.updateById(order);
        return order;
    }

    @Override
    public InboundOrder cancelInbound(Long id) {
        InboundOrder order = mustExist(id);
        if ("RECEIVED".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus())) {
            throw new IllegalStateException("已收货 / 已取消的入库单不可取消，当前=" + order.getStatus());
        }
        order.setStatus("CANCELLED");
        inboundOrderMapper.updateById(order);
        return order;
    }

    private InboundOrder mustExist(Long id) {
        InboundOrder order = inboundOrderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("入库单不存在：id=" + id);
        }
        return order;
    }
}
