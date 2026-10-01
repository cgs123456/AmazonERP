package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.util.BizNoGenerator;

import com.amz.mapper.OutboundOrderMapper;
import com.amz.model.OutboundOrder;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.OutboundService;
import com.amz.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import static com.amz.service.impl.LogisticsShopGuard.requireShopAllowed;

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
        if (order == null) {
            throw new CodeErrorException("出库单内容不能为空");
        }
        requireShopAllowed(order.getShopId(), "出库单");
        order.setOutboundNo(BizNoGenerator.next("OUT"));
        // 创建入口只允许产生初始态，客户端不能绕过拣货/打包直接写入 SHIPPED
        order.setStatus("PENDING");
        if (order.getShippedItems() == null) {
            order.setShippedItems(0);
        }
        outboundOrderMapper.insert(order);
        return order;
    }

    @Override
    public PageResult<OutboundOrder> listOutboundOrders(Long shopId, String status, PageRequest page) {
        requireShopAllowed(shopId, "出库单");
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
        LambdaQueryWrapper<OutboundOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OutboundOrder::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(OutboundOrder::getStatus, status);
        }
        Long cursorId = page.cursorId();
        if (cursorId != null) {
            wrapper.lt(OutboundOrder::getId, cursorId);
        }
        wrapper.orderByDesc(OutboundOrder::getId)
                .last("LIMIT " + page.probeSize());
        return PageResult.of(outboundOrderMapper.selectList(wrapper), page.size(),
                item -> PageRequest.encodeCursor(item.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundOrder pickOutbound(Long id) {
        OutboundOrder order = mustExist(id);
        if (!"PENDING".equals(order.getStatus())) {
            throw new CodeErrorException("仅 PENDING 状态可开始拣货，当前=" + order.getStatus());
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
            throw new CodeErrorException("仅 PICKING 状态可打包，当前=" + order.getStatus());
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
            throw new CodeErrorException("仅 PACKED 状态可发货，当前=" + order.getStatus());
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
    @Transactional(rollbackFor = Exception.class)
    public OutboundOrder cancelOutbound(Long id) {
        OutboundOrder order = mustExist(id);
        if (!"PENDING".equals(order.getStatus()) && !"PICKING".equals(order.getStatus())) {
            throw new CodeErrorException("仅 PENDING / PICKING 状态可取消，当前=" + order.getStatus());
        }
        order.setStatus("CANCELLED");
        outboundOrderMapper.updateById(order);
        return order;
    }

    private OutboundOrder mustExist(Long id) {
        if (id == null) {
            throw new CodeErrorException("出库单 ID 不能为空");
        }
        OutboundOrder order = outboundOrderMapper.selectById(id);
        if (order == null || !UserContext.isShopAllowed(order.getShopId())) {
            throw new CodeErrorException("出库单不存在或无权访问");
        }
        return order;
    }

}
