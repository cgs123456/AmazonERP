package com.amz.service.impl;

import com.amz.mapper.OutboundOrderMapper;
import com.amz.model.OutboundOrder;
import com.amz.model.WarehouseInventory;
import com.amz.service.OutboundService;
import com.amz.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出库流程服务实现。
 * 状态机：PENDING → PICKING → PACKED → SHIPPED / CANCELLED
 */
@Service
public class OutboundServiceImpl implements OutboundService {

    @Autowired
    private OutboundOrderMapper outboundOrderMapper;

    @Autowired
    private WarehouseService warehouseService;

    @Override
    public OutboundOrder createOutboundOrder(OutboundOrder order) {
        order.setOutboundNo("OUT" + System.currentTimeMillis());
        if (order.getStatus() == null) {
            order.setStatus("PENDING");
        }
        if (order.getShippedItems() == null) {
            order.setShippedItems(0);
        }
        outboundOrderMapper.insert(order);
        return order;
    }

    @Override
    public List<OutboundOrder> listOutboundOrders(Long shopId, String status) {
        LambdaQueryWrapper<OutboundOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OutboundOrder::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(OutboundOrder::getStatus, status);
        }
        wrapper.orderByDesc(OutboundOrder::getId);
        return outboundOrderMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundOrder pickOutbound(Long id) {
        OutboundOrder order = mustExist(id);
        if (!"PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("仅 PENDING 状态可开始拣货，当前=" + order.getStatus());
        }
        order.setStatus("PICKING");
        outboundOrderMapper.updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundOrder packOutbound(Long id) {
        OutboundOrder order = mustExist(id);
        if (!"PICKING".equals(order.getStatus())) {
            throw new IllegalStateException("仅 PICKING 状态可打包，当前=" + order.getStatus());
        }
        order.setStatus("PACKED");
        outboundOrderMapper.updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundOrder shipOutbound(Long id, String carrier, String trackingNo, List<WarehouseInventory> items) {
        OutboundOrder order = mustExist(id);
        if (!"PACKED".equals(order.getStatus())) {
            throw new IllegalStateException("仅 PACKED 状态可发货，当前=" + order.getStatus());
        }
        // 扣减库存
        if (items != null) {
            int shipped = order.getShippedItems() == null ? 0 : order.getShippedItems();
            for (WarehouseInventory item : items) {
                if (item.getQuantity() == null || item.getQuantity() <= 0) {
                    continue;
                }
                warehouseService.decreaseInventory(
                        order.getWarehouseId(),
                        item.getSku(),
                        item.getQuantity());
                shipped += item.getQuantity();
            }
            order.setShippedItems(shipped);
        }
        order.setCarrier(carrier);
        order.setTrackingNo(trackingNo);
        order.setShipDate(LocalDateTime.now());
        order.setStatus("SHIPPED");
        outboundOrderMapper.updateById(order);
        return order;
    }

    @Override
    public OutboundOrder cancelOutbound(Long id) {
        OutboundOrder order = mustExist(id);
        if (!"PENDING".equals(order.getStatus()) && !"PICKING".equals(order.getStatus())) {
            throw new IllegalStateException("仅 PENDING / PICKING 状态可取消，当前=" + order.getStatus());
        }
        order.setStatus("CANCELLED");
        outboundOrderMapper.updateById(order);
        return order;
    }

    private OutboundOrder mustExist(Long id) {
        OutboundOrder order = outboundOrderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("出库单不存在：id=" + id);
        }
        return order;
    }
}
