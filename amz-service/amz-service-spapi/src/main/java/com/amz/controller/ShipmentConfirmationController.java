package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.context.UserContext;
import com.amz.result.Result;
import com.amz.service.ShipmentConfirmationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Amazon 发货回传端点（FBM 确认发货）。
 * <p>
 * 由 order 域在本地订单确认后调用（Feign），因此同时接受
 * <b>用户 JWT</b>（运营在订单页直接操作）与 <b>order 服务令牌</b>（内部调用）。
 * 两条路径都做店铺归属校验：用户走 {@code @ShopScoped} + 显式严格判定，
 * 服务身份走 {@code @InternalServiceAccess} 白名单，与
 * {@code FinancialDataController} 的双信任边界一致。
 * <p>
 * 之所以把端点放在 spapi 而不是 order 服务：Amazon 的 {@code confirmShipment} 需要
 * 连接器层的凭证、区域端点、限流与 Outbox；order 域没有这些基础设施，
 * 自己发 HTTP 等于在业务层再造一套 SP-API 客户端（仓库明确禁止：所有出站必须走
 * 共享网关）。
 */
@RestController
@RequestMapping("/spapi/shipment")
public class ShipmentConfirmationController {

    private static final Logger log = LoggerFactory.getLogger(ShipmentConfirmationController.class);

    private final ShipmentConfirmationService shipmentConfirmationService;

    public ShipmentConfirmationController(ShipmentConfirmationService shipmentConfirmationService) {
        this.shipmentConfirmationService = shipmentConfirmationService;
    }

    /**
     * 向 Amazon 确认发货。
     * <p>
     * 返回 {@code success(true)} 仅代表 Amazon 已受理（官方 204）。
     * 本地订单状态由调用方在此之后回写——平台失败时本地绝不先改。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @InternalServiceAccess("amz-service-order")
    @PostMapping("/confirm")
    public Result<Boolean> confirm(@RequestBody ConfirmShipmentRequest request) {
        if (request == null || request.getShopId() == null) {
            return Result.failure("shopId 不能为空", ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (request.getAmazonOrderId() == null || request.getAmazonOrderId().isBlank()) {
            return Result.failure("amazonOrderId 不能为空",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        // 双信任店铺校验：用户令牌走 UserContext 授权店铺；order 服务令牌走服务身份。
        // 这与 FeedsController.submit 同一条防线，删掉它这个端点就成了无归属校验的写入口。
        if (!UserContext.isShopAllowedByUserOrTrustedService(request.getShopId())) {
            log.warn("发货回传店铺越权被拒 shopId={} userId={}", request.getShopId(), UserContext.getUserId());
            return Result.failure("无权限操作该店铺 shopId=" + request.getShopId(),
                    ErrorSummary.localError(LocalApiException.CODE_FORBIDDEN));
        }

        List<ShipmentConfirmationService.OrderItemLine> lines = new ArrayList<>();
        if (request.getOrderItems() != null) {
            for (OrderItemLineDto dto : request.getOrderItems()) {
                if (dto != null) {
                    lines.add(new ShipmentConfirmationService.OrderItemLine(
                            dto.getOrderItemId(), dto.getQuantity()));
                }
            }
        }

        try {
            shipmentConfirmationService.confirmShipment(
                    request.getShopId(),
                    request.getMarketplaceId(),
                    request.getAmazonOrderId().trim(),
                    request.getCarrierCode(),
                    request.getCarrierName(),
                    request.getTrackingNumber(),
                    parseShipDate(request.getShipDate()),
                    lines);
            // 204 无响应体：网关返回空 JSON 对象。这里只回布尔，不回传上游原始响应，
            // 避免把可能的内部字段（含 requestId 等）透给调用方。
            return Result.success(true);
        } catch (Exception e) {
            log.error("发货回传失败 shopId={} orderId={}", request.getShopId(),
                    request.getAmazonOrderId(), e);
            return Result.failure("发货回传失败：" + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }

    /**
     * 解析发货时间。空值交由服务层取当前 UTC 时间；
     * 给了但格式非法时<b>明确失败</b>而不是静默改用当前时间——
     * 静默替换会把「运营填错了日期」变成「系统记了一个它自己编的日期」。
     */
    private OffsetDateTime parseShipDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception e) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "shipDate 必须是 ISO-8601 带偏移的时间，例如 2026-10-11T09:30:00Z，实际收到「" + raw + "」");
        }
    }

    /**
     * 发货回传请求体。
     */
    public static class ConfirmShipmentRequest {
        private Long shopId;
        private String marketplaceId;
        private String amazonOrderId;
        private String carrierCode;
        private String carrierName;
        private String trackingNumber;
        /** ISO-8601 带偏移，可空（空则用当前 UTC 时间）。 */
        private String shipDate;
        private List<OrderItemLineDto> orderItems;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }

        public String getMarketplaceId() {
            return marketplaceId;
        }

        public void setMarketplaceId(String marketplaceId) {
            this.marketplaceId = marketplaceId;
        }

        public String getAmazonOrderId() {
            return amazonOrderId;
        }

        public void setAmazonOrderId(String amazonOrderId) {
            this.amazonOrderId = amazonOrderId;
        }

        public String getCarrierCode() {
            return carrierCode;
        }

        public void setCarrierCode(String carrierCode) {
            this.carrierCode = carrierCode;
        }

        public String getCarrierName() {
            return carrierName;
        }

        public void setCarrierName(String carrierName) {
            this.carrierName = carrierName;
        }

        public String getTrackingNumber() {
            return trackingNumber;
        }

        public void setTrackingNumber(String trackingNumber) {
            this.trackingNumber = trackingNumber;
        }

        public String getShipDate() {
            return shipDate;
        }

        public void setShipDate(String shipDate) {
            this.shipDate = shipDate;
        }

        public List<OrderItemLineDto> getOrderItems() {
            return orderItems;
        }

        public void setOrderItems(List<OrderItemLineDto> orderItems) {
            this.orderItems = orderItems;
        }
    }

    /** 一行待确认明细。 */
    public static class OrderItemLineDto {
        private String orderItemId;
        private Integer quantity;

        public String getOrderItemId() {
            return orderItemId;
        }

        public void setOrderItemId(String orderItemId) {
            this.orderItemId = orderItemId;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }
}
