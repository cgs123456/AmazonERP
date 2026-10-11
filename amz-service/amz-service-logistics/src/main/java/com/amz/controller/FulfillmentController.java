package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import com.amz.result.Result;
import com.amz.service.impl.FulfillmentSupportService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单履约支撑端点（P2.1 配货履约流，供 order 服务编排）。
 * <p>
 * <b>职责边界</b>：order 服务负责「订单→履约」编排（校验订单状态、决定何时扣库存），
 * logistics 只提供两个无状态动作——<b>库存可用性检查</b>与<b>出库执行（建单即发运）</b>。
 * 出库执行复用既有出库单状态机（建单→拣→打包→发运）并写库存流水，
 * 不在 order 服务里另造一套扣减逻辑。
 * <p>
 * 鉴权：{@code @InternalServiceAccess("amz-service-order")}，仅内部服务令牌可达。
 */
@Slf4j
@RestController
@RequestMapping("/logistics/fulfillment")
public class FulfillmentController {

    @Autowired
    private FulfillmentSupportService fulfillmentSupportService;

    /**
     * 库存可用性检查：返回每个 SKU 是否足量（不扣减）。
     */
    @InternalServiceAccess("amz-service-order")
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/check")
    public Result<List<SkuAvailability>> check(@RequestBody StockCheckRequest request) {
        if (request == null || request.getShopId() == null || request.getWarehouseId() == null
                || request.getItems() == null || request.getItems().isEmpty()) {
            return Result.failure("shopId / warehouseId / items 均不能为空");
        }
        try {
            return Result.success(fulfillmentSupportService.checkStock(
                    request.getShopId(), request.getWarehouseId(), request.getItems()));
        } catch (Exception e) {
            log.warn("库存可用性检查失败 shopId={}: {}", request.getShopId(), e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 出库执行：创建出库单（类型 ORDER，关联 Amazon 订单号）并直接推进到 SHIPPED
     * （扣库存 + 写流水）。任一 SKU 扣减失败整体失败（事务回滚），返回点名明细。
     */
    @InternalServiceAccess("amz-service-order")
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/outbound")
    public Result<FulfillmentOutboundResult> outbound(@RequestBody FulfillmentOutboundRequest request) {
        if (request == null || request.getShopId() == null || request.getWarehouseId() == null
                || request.getReferenceNo() == null || request.getItems() == null
                || request.getItems().isEmpty()) {
            return Result.failure("shopId / warehouseId / referenceNo / items 均不能为空");
        }
        try {
            return Result.success(fulfillmentSupportService.executeOutbound(request));
        } catch (Exception e) {
            log.warn("履约出库失败 referenceNo={}: {}", request.getReferenceNo(), e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /** 可用性检查请求。 */
    @Data
    public static class StockCheckRequest {
        private Long shopId;
        private Long warehouseId;
        private List<SkuQty> items;
    }

    /** SKU + 数量。 */
    @Data
    public static class SkuQty {
        private String sku;
        private Integer quantity;
    }

    /** 单 SKU 可用性。 */
    @Data
    public static class SkuAvailability {
        private String sku;
        private Integer required;
        private Integer available;
        private boolean sufficient;

        public SkuAvailability() {
        }

        public SkuAvailability(String sku, Integer required, Integer available) {
            this.sku = sku;
            this.required = required;
            this.available = available;
            this.sufficient = available != null && required != null && available >= required;
        }
    }

    /** 出库执行请求。 */
    @Data
    public static class FulfillmentOutboundRequest {
        private Long shopId;
        private Long warehouseId;
        /** 关联单号（Amazon 订单号） */
        private String referenceNo;
        private List<SkuQty> items;
    }

    /** 出库执行结果。 */
    @Data
    public static class FulfillmentOutboundResult {
        private Long outboundId;
        private String outboundNo;
    }
}
