package com.amz.service.impl;

import com.amz.client.SpApiShipmentClient;
import com.amz.client.dto.ShipmentConfirmRequest;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OrderItemMapper;
import com.amz.mapper.OrderMapper;
import com.amz.model.pojo.Order;
import com.amz.model.pojo.OrderItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单发货回传服务（FBM 确认发货）。
 * <p>
 * <b>存在动因</b>：此前 {@code amz_order.tracking_number} 零写入点、
 * {@code sync_status} 的「2=已上传跟踪号」从未被写过——FBM 卖家在系统里
 * 完全没有「上传运单号 → 通知 Amazon」这条链路，只能去卖家后台手工确认。
 * <p>
 * <b>核心不变量（照抄 MultiplatformServiceImpl.markShipped）</b>：
 * 只有 spapi 明确返回成功，本地订单才写入 tracking/carrier/fulfillment_status；
 * 平台失败或降级时本地状态<b>原样不动</b>。把「调用失败」写成「已发货」，
 * 会造成 Amazon 侧超时未发货，直接影响卖家账号绩效。
 * <p>
 * <b>为什么在这里读订单行</b>：Amazon 的 confirmShipment 要求逐行 orderItemId+quantity，
 * 而订单行的事实源在 order 库（amz_order_item）。让 spapi 反过来查 order 库
 * 会引入一个反方向的跨服务依赖；由 order 域读出并随请求带上更自然。
 */
@Slf4j
@Service
public class OrderFulfillmentService {

    /** 回传状态取值（与 V6 迁移注释一致，DB 层不加 CHECK）。 */
    static final String STATUS_NOT_CONFIRMED = "NOT_CONFIRMED";
    static final String STATUS_CONFIRMED = "CONFIRMED";

    /** 承运商代码最大长度，对齐 amz_order.carrier_code VARCHAR(50)。 */
    private static final int CARRIER_CODE_MAX = 50;
    private static final int CARRIER_NAME_MAX = 100;
    private static final int TRACKING_MAX = 100;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    /** 可选注入：单测里可缺席，缺席时按「不可用」处理（fail-closed，不改本地状态）。 */
    @Autowired(required = false)
    private SpApiShipmentClient spApiShipmentClient;

    /**
     * 确认发货：先向 Amazon 回传，成功后才回写本地。
     *
     * @param orderId        本地订单主键
     * @param carrierCode    承运商代码（必填）
     * @param carrierName    承运商名称（carrierCode=Other 时必填）
     * @param trackingNumber 运单号（必填）
     * @param shipDate       发货时间（ISO-8601 带偏移，可空）
     * @return 回传成功后的订单（本地状态已更新）
     */
    public Order confirmShipment(Long orderId, String carrierCode, String carrierName,
                                 String trackingNumber, String shipDate) {
        if (orderId == null) {
            throw new CodeErrorException("订单 ID 不能为空");
        }
        if (!StringUtils.hasText(trackingNumber)) {
            throw new CodeErrorException("运单号不能为空");
        }
        if (!StringUtils.hasText(carrierCode)) {
            throw new CodeErrorException("承运商代码不能为空（Amazon 要求所有站点必填）");
        }
        String carrier = carrierCode.trim();
        String carrierDisplay = carrierName == null ? null : carrierName.trim();
        String tracking = trackingNumber.trim();
        if (carrier.length() > CARRIER_CODE_MAX) {
            throw new CodeErrorException("承运商代码最长 " + CARRIER_CODE_MAX + " 字符");
        }
        if (carrierDisplay != null && carrierDisplay.length() > CARRIER_NAME_MAX) {
            throw new CodeErrorException("承运商名称最长 " + CARRIER_NAME_MAX + " 字符");
        }
        if (tracking.length() > TRACKING_MAX) {
            throw new CodeErrorException("运单号最长 " + TRACKING_MAX + " 字符");
        }
        // Amazon 官方：carrierCode=Other 时必须提供 carrierName，否则请求会被拒。
        // 在本地先拒绝，报错能指向「缺承运商名称」而不是让 Amazon 回一个泛泛的 400。
        if ("Other".equalsIgnoreCase(carrier) && !StringUtils.hasText(carrierDisplay)) {
            throw new CodeErrorException("carrierCode 为 Other 时必须提供承运商名称");
        }

        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new CodeErrorException("订单不存在：id=" + orderId);
        }
        // 归属校验：以资源 ID 为入参的端点没有 shopId 可供切面检查，
        // 必须在服务层按 UserContext 校验（与 OrderAuditServiceImpl 同一约定）。
        if (!UserContext.isShopAllowedStrict(order.getShopId())) {
            throw new CodeErrorException("订单不存在或无权访问");
        }
        if (STATUS_CONFIRMED.equals(order.getFulfillmentStatus())) {
            // 重复回传：Amazon 对同一订单重复 confirmShipment 会报错，
            // 这里明确拒绝而不是静默放过，避免调用方以为「又发了一次成功」。
            throw new CodeErrorException("该订单已回传发货（" + order.getShipConfirmedAt() + "），无需重复操作");
        }
        if (!StringUtils.hasText(order.getAmazonOrderId())) {
            throw new CodeErrorException("订单缺少 Amazon 订单号，无法回传（可能尚未同步完成）");
        }
        if (spApiShipmentClient == null) {
            throw new CodeErrorException("发货回传通道不可用（spapi 客户端未装配）");
        }

        List<OrderItem> items = loadOrderItems(order);
        if (items.isEmpty()) {
            // 不构造空行数组蒙混：Amazon 会拒，且失败原因会指向字段校验而非真实缺口
            throw new CodeErrorException("订单没有明细行，无法回传发货（Amazon 要求逐行确认）");
        }

        ShipmentConfirmRequest request = new ShipmentConfirmRequest();
        request.setShopId(order.getShopId());
        request.setMarketplaceId(order.getMarketplaceId());
        request.setAmazonOrderId(order.getAmazonOrderId());
        request.setCarrierCode(carrier);
        request.setCarrierName(carrierDisplay);
        request.setTrackingNumber(tracking);
        request.setShipDate(normalizeShipDate(shipDate));
        List<ShipmentConfirmRequest.OrderItemLine> lines = new ArrayList<>();
        for (OrderItem item : items) {
            ShipmentConfirmRequest.OrderItemLine line = new ShipmentConfirmRequest.OrderItemLine();
            line.setOrderItemId(item.getAmazonOrderItemId());
            line.setQuantity(item.getQuantity());
            lines.add(line);
        }
        request.setOrderItems(lines);

        Boolean accepted;
        try {
            accepted = spApiShipmentClient.confirmShipment(request);
        } catch (Exception e) {
            // 调用异常（含熔断降级）：本地状态保持未回传，把原因点名抛给调用方
            log.error("发货回传调用失败 shopId={} orderId={}", order.getShopId(), order.getAmazonOrderId(), e);
            throw new CodeErrorException("发货回传调用失败：" + e.getMessage());
        }
        if (!Boolean.TRUE.equals(accepted)) {
            // 平台未受理：绝不改本地状态（这是本服务最重要的不变量）
            log.warn("发货回传未被 Amazon 受理，本地状态保持不变 orderId={} tracking={}",
                    order.getAmazonOrderId(), tracking);
            throw new CodeErrorException("Amazon 未受理本次发货回传，本地订单状态未变更");
        }

        // 只有到这里才写本地
        order.setTrackingNumber(tracking);
        order.setCarrierCode(carrier);
        order.setCarrierName(carrierDisplay);
        order.setFulfillmentStatus(STATUS_CONFIRMED);
        order.setShipConfirmedAt(LocalDateTime.now());
        // sync_status 推进到 2（已上传跟踪号）——这个取值在 V1 注释里写了但从未被写过，
        // 本轮建立它的写入路径。3（已完成）留给后续「Amazon 确认送达」类信号。
        order.setSyncStatus(2);
        orderMapper.updateById(order);
        log.info("发货回传成功并回写本地 shopId={} orderId={} tracking={} carrier={}",
                order.getShopId(), order.getAmazonOrderId(), tracking, carrier);
        return order;
    }

    /** 读取订单明细行（按 Amazon 订单号 + 店铺，避免跨店同号串行）。 */
    private List<OrderItem> loadOrderItems(Order order) {
        LambdaQueryWrapper<OrderItem> q = new LambdaQueryWrapper<>();
        q.eq(OrderItem::getShopId, order.getShopId())
                .eq(OrderItem::getAmazonOrderId, order.getAmazonOrderId());
        return orderItemMapper.selectList(q);
    }

    /**
     * 归一化发货时间：空值交由 spapi 取当前 UTC；给了就原样传（spapi 会做 ISO-8601 校验）。
     * 这里只做「空转 null」，不做「非法值静默替换成当前时间」——
     * 后者会把「运营填错了日期」变成「系统编了一个日期」。
     */
    private String normalizeShipDate(String shipDate) {
        if (!StringUtils.hasText(shipDate)) {
            return null;
        }
        String trimmed = shipDate.trim();
        try {
            OffsetDateTime.parse(trimmed);
        } catch (Exception e) {
            throw new CodeErrorException("发货时间必须是 ISO-8601 带偏移的时间，例如 2026-10-11T09:30:00Z，"
                    + "实际收到「" + shipDate + "」");
        }
        return trimmed;
    }

    /** 便于测试与外部查询：当前订单是否已回传。 */
    public boolean isConfirmed(Order order) {
        return order != null && STATUS_CONFIRMED.equals(order.getFulfillmentStatus());
    }

    /**
     * 组合装展开：逐行查 BOM，有子品的替换为子品需求，无子品的原样保留。
     * <p>
     * BOM 通道未装配（未配置）或调用失败时<b>不</b>把组合行当叶子继续——
     * 「展开失败」与「真的没有 BOM」必须区分：前者中止（可重试），
     * 后者才继续。仅当 procurement 明确返回空展开结果（所有行都是叶子）时才走原样路径。
     */
    private List<OrderItem> expandBomIfNeeded(Long shopId, List<OrderItem> items) {
        if (procurementBomClient == null || items.isEmpty()) {
            return items;
        }
        boolean anyCompositePossible = items.stream()
                .anyMatch(i -> i.getSellerSku() != null);
        if (!anyCompositePossible) {
            return items;
        }
        try {
            List<java.util.Map<String, Object>> requests = new ArrayList<>();
            for (OrderItem i : items) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("sku", i.getSellerSku() == null ? "" : i.getSellerSku());
                row.put("quantity", i.getQuantity() == null ? 0 : i.getQuantity());
                requests.add(row);
            }
            com.amz.result.Result<java.util.List<java.util.Map<String, Object>>> expanded =
                    procurementBomClient.expand(shopId, requests);
            if (expanded == null || expanded.getCode() != 200) {
                // 展开失败 ≠ 无 BOM：中止配货（可重试），不假装继续
                throw new CodeErrorException("BOM 展开失败，配货中止："
                        + (expanded == null ? "无响应" : expanded.getMessage()));
            }
            List<OrderItem> result = new ArrayList<>();
            for (java.util.Map<String, Object> row : expanded.getData()) {
                OrderItem line = new OrderItem();
                line.setShopId(shopId);
                line.setAmazonOrderId(items.get(0).getAmazonOrderId());
                line.setSellerSku(String.valueOf(row.get("sku")));
                line.setQuantity(((Number) row.get("quantity")).intValue());
                result.add(line);
            }
            log.info("BOM 展开：{} 行明细 → {} 行子品需求 shopId={}",
                    items.size(), result.size(), shopId);
            return result;
        } catch (CodeErrorException e) {
            throw e;
        } catch (Exception e) {
            throw new CodeErrorException("BOM 展开异常，配货中止：" + e.getMessage());
        }
    }

    /** 回传时间用系统时区展示时的默认偏移（仅用于日志/展示，不参与持久化）。 */
    static ZoneOffset defaultOffset() {
        return ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now());
    }

    // ==================== 配货出库（P2.1，本地仓履约） ====================

    @Autowired(required = false)
    private com.amz.client.LogisticsFulfillmentClient logisticsFulfillmentClient;

    /** BOM 展开（可选注入）：组合装售出 1 个组合 SKU，实际出库 N 个子品。 */
    @Autowired(required = false)
    private com.amz.client.ProcurementBomClient procurementBomClient;

    /**
     * 配货出库：MFN 订单从本地仓出库（检查库存 → 出库执行 → 标记 ALLOCATED）。
     * <p>
     * <b>两步语义</b>：本方法只负责「本地仓扣减」（fulfillment_status=ALLOCATED），
     * <b>不</b>自动向 Amazon 确认发货——确认发货需要真实承运商与运单号，
     * 由运营在出库后走 {@link #confirmShipment}。把两步合一会造成
     * 「Amazon 已受理但本地扣减失败」的分布式回滚难题。
     * <p>
     * AFN（FBA）订单不走本方法：FBA 履约由 Amazon 完成，本地仓无货可扣。
     */
    public Order allocateStock(Long orderId, Long warehouseId) {
        if (orderId == null || warehouseId == null) {
            throw new CodeErrorException("订单 ID 与仓库 ID 均不能为空");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new CodeErrorException("订单不存在：id=" + orderId);
        }
        if (!UserContext.isShopAllowedStrict(order.getShopId())) {
            throw new CodeErrorException("订单不存在或无权访问");
        }
        if (!"MFN".equalsIgnoreCase(order.getFulfillmentChannel())) {
            throw new CodeErrorException("仅自发货（MFN）订单可本地配货，当前履约渠道="
                    + order.getFulfillmentChannel() + "（FBA 由 Amazon 履约）");
        }
        if ("ALLOCATED".equals(order.getFulfillmentStatus())) {
            throw new CodeErrorException("该订单已配货（" + order.getShipConfirmedAt() + "），无需重复操作");
        }
        if (spApiShipmentClient == null || logisticsFulfillmentClient == null) {
            throw new CodeErrorException("配货通道不可用（Feign 客户端未装配）");
        }
        if (!StringUtils.hasText(order.getAmazonOrderId())) {
            throw new CodeErrorException("订单缺少 Amazon 订单号，无法配货");
        }
        List<OrderItem> items = loadOrderItems(order);
        if (items.isEmpty()) {
            throw new CodeErrorException("订单没有明细行，无法配货（Amazon 要求逐行履约）");
        }

        // ① BOM 展开（P2.4）：组合装售出 1 个组合 SKU，实际出库 N 个子品；
        // 本地仓按子品备货，组合 SKU 没有库存行。展开失败即中止——
        // 把「展开失败」当「无 BOM」继续扣，会去扣一个不存在的组合行。
        List<OrderItem> effectiveItems = expandBomIfNeeded(order.getShopId(), items);

        // ② 库存检查（不扣减）：不足则点名 SKU，一个都不扣。
        // 本地仓按 sellerSku 备货——检查与出库都用 sellerSku；
        // 明细行的 orderItemId 是 Amazon 行 ID，只在确认发货时用于 Amazon 侧
        java.util.Map<String, Object> checkRequest = new java.util.LinkedHashMap<>();
        checkRequest.put("shopId", order.getShopId());
        checkRequest.put("warehouseId", warehouseId);
        List<java.util.Map<String, Object>> checkItems = new ArrayList<>();
        for (OrderItem i : effectiveItems) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("sku", i.getSellerSku() == null ? "" : i.getSellerSku());
            row.put("quantity", i.getQuantity() == null ? 0 : i.getQuantity());
            checkItems.add(row);
        }
        checkRequest.put("items", checkItems);
        com.amz.result.Result<java.util.List<java.util.Map<String, Object>>> check =
                logisticsFulfillmentClient.checkStock(checkRequest);
        if (check == null || check.getCode() != 200 || check.getData() == null) {
            throw new CodeErrorException("库存检查失败："
                    + (check == null ? "无响应" : check.getMessage()));
        }
        List<String> insufficient = check.getData().stream()
                .filter(row -> !Boolean.TRUE.equals(row.get("sufficient")))
                .map(row -> String.valueOf(row.get("sku")))
                .toList();
        if (!insufficient.isEmpty()) {
            throw new CodeErrorException("以下 SKU 本地仓库存不足，未扣减任何库存：" + insufficient);
        }

        // ② 出库执行（logistics 整体事务：失败即回滚，无半扣减中间态）
        java.util.Map<String, Object> outboundRequest = new java.util.LinkedHashMap<>();
        outboundRequest.put("shopId", order.getShopId());
        outboundRequest.put("warehouseId", warehouseId);
        outboundRequest.put("referenceNo", order.getAmazonOrderId());
        List<java.util.Map<String, Object>> outboundItems = new ArrayList<>();
        for (OrderItem i : effectiveItems) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("sku", i.getSellerSku() == null ? "" : i.getSellerSku());
            row.put("quantity", i.getQuantity() == null ? 0 : i.getQuantity());
            outboundItems.add(row);
        }
        outboundRequest.put("items", outboundItems);
        com.amz.result.Result<java.util.Map<String, Object>> outbound =
                logisticsFulfillmentClient.executeOutbound(outboundRequest);
        if (outbound == null || outbound.getCode() != 200 || outbound.getData() == null) {
            throw new CodeErrorException("配货出库失败："
                    + (outbound == null ? "无响应" : outbound.getMessage()));
        }

        // ③ 标记已配货
        order.setFulfillmentStatus("ALLOCATED");
        orderMapper.updateById(order);
        log.info("配货出库完成 shopId={} orderId={} outboundNo={}",
                order.getShopId(), order.getAmazonOrderId(), outbound.getData().get("outboundNo"));
        return order;
    }
}
