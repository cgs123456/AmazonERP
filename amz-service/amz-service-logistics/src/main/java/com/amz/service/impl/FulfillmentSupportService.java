package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.controller.FulfillmentController;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.model.OutboundOrder;
import com.amz.model.WarehouseInventory;
import com.amz.service.OutboundService;
import com.amz.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单履约支撑服务（P2.1 配货履约流的 logistics 侧）。
 * <p>
 * order 服务编排列「检查 → 出库」两步；本服务提供可复用的无状态实现：
 * <ul>
 *   <li>{@link #checkStock}：逐 SKU 报可用量，不扣减；</li>
 *   <li>{@link #executeOutbound}：创建出库单（类型 ORDER，referenceNo=Amazon 订单号）并
 *       推进到 SHIPPED——扣库存、写流水全部复用既有出库链路；任一 SKU 失败整体回滚。</li>
 * </ul>
 * <b>调度线程身份</b>：调用方（order 服务）经 Feign 进来时带的是服务令牌，
 * {@code requireShopAllowed}/{@code UserContext} 语义与人工操作一致（内部服务身份放行）。
 */
@Slf4j
@Service
public class FulfillmentSupportService {

    @Autowired
    private OutboundService outboundService;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private WarehouseInventoryMapper inventoryMapper;

    /** 逐 SKU 检查可用量（不扣减）。 */
    public List<FulfillmentController.SkuAvailability> checkStock(
            Long shopId, Long warehouseId, List<FulfillmentController.SkuQty> items) {
        LogisticsShopGuard.requireShopAllowed(shopId, "库存");
        List<FulfillmentController.SkuAvailability> result = new ArrayList<>();
        for (FulfillmentController.SkuQty item : items) {
            if (item == null || !StringUtils.hasText(item.getSku())) {
                throw new CodeErrorException("库存检查明细缺少 SKU");
            }
            int qty = item.getQuantity() == null ? 0 : item.getQuantity();
            if (qty <= 0) {
                throw new CodeErrorException("库存检查数量必须大于 0：sku=" + item.getSku());
            }
            int available = warehouseService.currentQuantity(warehouseId, shopId, item.getSku().trim());
            result.add(new FulfillmentController.SkuAvailability(item.getSku().trim(), qty, available));
        }
        return result;
    }

    /**
     * 出库执行：建单 + 拣/打包/发运一气呵成，全部复用既有出库状态机（含扣库存与流水）。
     * <p>
     * 任一 SKU 失败抛异常整体回滚——「发了三个 SKU、第四个没货」的半履约状态
     * 比全失败更难处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public FulfillmentController.FulfillmentOutboundResult executeOutbound(
            FulfillmentController.FulfillmentOutboundRequest request) {
        LogisticsShopGuard.requireShopAllowed(request.getShopId(), "出库单");
        int total = request.getItems().stream()
                .mapToInt(item -> item.getQuantity() == null ? 0 : item.getQuantity())
                .sum();

        OutboundOrder order = new OutboundOrder();
        order.setShopId(request.getShopId());
        order.setWarehouseId(request.getWarehouseId());
        order.setOrderType("ORDER");
        order.setReferenceNo(request.getReferenceNo().trim());
        order.setTotalItems(total);
        OutboundOrder created = outboundService.createOutboundOrder(order);

        // 拣 → 打包 → 发运（发运扣库存并写流水，复用 OutboundServiceImpl）
        outboundService.pickOutbound(created.getId());
        outboundService.packOutbound(created.getId());

        List<WarehouseInventory> shipItems = new ArrayList<>();
        for (FulfillmentController.SkuQty item : request.getItems()) {
            WarehouseInventory line = new WarehouseInventory();
            line.setSku(item.getSku().trim());
            line.setQuantity(item.getQuantity());
            shipItems.add(line);
        }
        // 履约自动出库的承运商/单号在「确认发货」一步由 Amazon 侧回填；
        // 这里发运记录用占位并在 remark 说明，真实运单号以 amz_order 为准
        OutboundOrder shipped = outboundService.shipOutbound(created.getId(),
                "MANUAL", request.getReferenceNo().trim(), shipItems);

        log.info("履约出库完成 outboundNo={} referenceNo={} skus={} total={}",
                shipped.getOutboundNo(), request.getReferenceNo(), request.getItems().size(), total);
        FulfillmentController.FulfillmentOutboundResult result =
                new FulfillmentController.FulfillmentOutboundResult();
        result.setOutboundId(shipped.getId());
        result.setOutboundNo(shipped.getOutboundNo());
        return result;
    }
}
