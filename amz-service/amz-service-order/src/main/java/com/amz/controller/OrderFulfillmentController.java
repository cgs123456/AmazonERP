package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.pojo.Order;
import com.amz.result.Result;
import com.amz.service.impl.OrderFulfillmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单发货回传接口（FBM 确认发货）。
 * <p>
 * 把「上传运单号 → 通知 Amazon」这条此前完全缺失的链路补上：此前
 * {@code amz_order.tracking_number} 零写入点，FBM 卖家只能去 Amazon 后台手工确认发货。
 * <p>
 * 鉴权：{@code @RequireRole OPERATOR/ADMIN} + 服务层按 {@code UserContext} 校验订单归属。
 * 本端点以订单 ID 为入参（无 shopId 可供切面检查），因此 {@code @ShopScoped} 只能覆盖
 * 请求头维度，真正的归属判定在 {@link OrderFulfillmentService} 里做。
 */
@Slf4j
@RestController
@RequestMapping("/order")
public class OrderFulfillmentController {

    @Autowired
    private OrderFulfillmentService orderFulfillmentService;

    /**
     * 确认发货：向 Amazon 回传承运商与运单号，成功后才回写本地订单。
     *
     * @param orderId        本地订单主键
     * @param carrierCode    承运商代码（必填；Amazon 要求所有站点必填）
     * @param trackingNumber 运单号（必填）
     * @param carrierName    承运商名称（carrierCode=Other 时必填）
     * @param shipDate       发货时间（ISO-8601 带偏移，可空）
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @OperLog(module = "订单发货", action = "回传", description = "向 Amazon 确认发货并回写本地运单号")
    @PostMapping("/{orderId}/confirm-ship")
    public Result<Order> confirmShip(@PathVariable Long orderId,
                                     @RequestParam String carrierCode,
                                     @RequestParam String trackingNumber,
                                     @RequestParam(required = false) String carrierName,
                                     @RequestParam(required = false) String shipDate) {
        try {
            return Result.success(orderFulfillmentService.confirmShipment(
                    orderId, carrierCode, carrierName, trackingNumber, shipDate));
        } catch (Exception e) {
            log.warn("发货回传失败 orderId={}: {}", orderId, e.getMessage());
            return Result.failure(e.getMessage());
        }
    }
}
