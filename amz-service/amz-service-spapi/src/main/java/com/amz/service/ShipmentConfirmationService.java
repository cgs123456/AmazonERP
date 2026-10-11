package com.amz.service;

import com.amz.client.OrdersClient;
import com.amz.connector.LocalApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Amazon 发货回传（FBM 卖家确认发货）。
 * <p>
 * <b>存在动因</b>：仓库此前完全没有发货回写路径——{@code amz_order.tracking_number}
 * 零写入点、{@code sync_status} 只有 {@code =1} 一处赋值（注释里的「2=已上传跟踪号」
 * 从未被写过）。也就是说 FBM 卖家在系统里看不到「上传运单号 → 通知 Amazon」这条链路，
 * 只能在卖家后台手工操作。这是 ERP 使用频率最高的动作之一，属于整块缺失。
 * <p>
 * <b>为什么用类型化客户端（{@link SpApiGateway} 直调）而不是统一 operation 目录</b>：
 * 与 {@code OrdersClient}（orders.getOrders / getOrderItems）同一条先例。
 * 统一目录的契约测试要求「目录条数 == 五份官方快照解析出的条数」，而 orders 快照
 * 不在那五份里；把 confirmShipment 塞进目录会连带要求为它补 mock fixture、改目录计数、
 * 改控制器顺序断言——那是把「目录」这个展示/执行面当成了发货回传的载体。
 * 发货回传是<b>业务动作</b>，走类型化客户端更贴合其性质：路径、成功码（204）、
 * 限流 operationId（orders.confirmShipment，官方 2 req/s burst 10）都显式声明在代码里，
 * 官方依据是 {@code contracts/ordersV0.json} 的 shipmentConfirmation 定义。
 * <p>
 * <b>成功状态是 204 不是 200</b>：官方快照明示。204 空体由网关转为空 JSON 对象，
 * 不会被误判成失败。
 * <p>
 * <b>本地状态回写不在这里</b>：本服务只负责「把事实告诉 Amazon 并如实返回结果」，
 * 订单表在 order 域，回写由调用方在<b>确认成功之后</b>执行——
 * 与 {@code MultiplatformServiceImpl.markShipped} 同一条不变量：
 * 平台返回失败时本地状态绝不先改。
 */
@Service
public class ShipmentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentConfirmationService.class);

    /** 官方 operationId（限流维度；档位 2 req/s、burst 10，见 SpiRateLimiter）。 */
    static final String OP_CONFIRM_SHIPMENT = OrdersClient.OP_CONFIRM_SHIPMENT;

    /** 官方 shipDate 是 ISO-8601 date-time；用 UTC 偏移表达，避免服务端时区歧义。 */
    private static final DateTimeFormatter SHIP_DATE_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final OrdersClient ordersClient;

    public ShipmentConfirmationService(OrdersClient ordersClient) {
        this.ordersClient = ordersClient;
    }

    /**
     * 向 Amazon 确认发货。
     *
     * @param shopId       店铺 ID
     * @param marketplaceId 站点 ID（必须与订单所在站点一致，否则 Amazon 拒绝）
     * @param amazonOrderId Amazon 订单号
     * @param carrierCode  承运商代码（Amazon 要求所有站点必填；用 "Other" 时需附 carrierName）
     * @param carrierName  承运商名称（carrierCode=Other 时必填）
     * @param trackingNumber 运单号
     * @param shipDate     发货时间（可空，默认当前 UTC 时间）
     * @param orderItems   逐行明细（orderItemId + quantity），必须与 Amazon 侧对应
     * @return 空对象（204 无响应体）；失败时抛类型化异常，绝不伪装成功
     */
    public JsonObject confirmShipment(Long shopId,
                                      String marketplaceId,
                                      String amazonOrderId,
                                      String carrierCode,
                                      String carrierName,
                                      String trackingNumber,
                                      OffsetDateTime shipDate,
                                      List<OrderItemLine> orderItems) {
        requireText(shopId, "shopId");
        requireText(amazonOrderId, "amazonOrderId");
        requireText(marketplaceId, "marketplaceId");
        requireText(carrierCode, "carrierCode");
        requireText(trackingNumber, "trackingNumber");
        if (orderItems == null || orderItems.isEmpty()) {
            // 不构造「空行数组」蒙混：Amazon 会拒，且失败原因会指向字段校验而非业务意图
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "orderItems must not be empty: Amazon requires the confirmed lines");
        }
        if ("Other".equalsIgnoreCase(carrierCode.trim())
                && (carrierName == null || carrierName.isBlank())) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    "carrierName is required when carrierCode is 'Other'");
        }

        JsonObject body = buildBody(marketplaceId, carrierCode, carrierName,
                trackingNumber, shipDate, orderItems);
        log.info("确认发货回传 Amazon shopId={} orderId={} carrier={} tracking={} lines={}",
                shopId, amazonOrderId, carrierCode, trackingNumber, orderItems.size());

        // 委托类型化客户端：路径、成功码 204、限流 operationId 都声明在 OrdersClient 里，
        // 与 orders.getOrders / getOrderItems 同一先例（业务动作不走统一 operation 目录）。
        return ordersClient.confirmShipment(shopId, marketplaceId, amazonOrderId, body);
    }

    /**
     * 构造官方 ConfirmShipmentRequest。字段名与嵌套层级严格按官方快照，
     * 不自行改写成更「顺手」的形状——Amazon 只认这一种。
     */
    private JsonObject buildBody(String marketplaceId, String carrierCode, String carrierName,
                                 String trackingNumber, OffsetDateTime shipDate,
                                 List<OrderItemLine> orderItems) {
        JsonObject packageDetail = new JsonObject();
        // packageReferenceId：本次发货的包裹引用。一个订单可能分多个包裹发出，
        // 用固定「PKG-1」表达「本系统一次回传一个包裹」；多次回传同一订单会被本地拒绝。
        packageDetail.addProperty("packageReferenceId", "PKG-1");
        packageDetail.addProperty("carrierCode", carrierCode.trim());
        if (carrierName != null && !carrierName.isBlank()) {
            packageDetail.addProperty("carrierName", carrierName.trim());
        }
        packageDetail.addProperty("trackingNumber", trackingNumber.trim());
        OffsetDateTime effectiveShipDate = shipDate != null
                ? shipDate : OffsetDateTime.now(ZoneOffset.UTC);
        packageDetail.addProperty("shipDate", effectiveShipDate.format(SHIP_DATE_FORMAT));

        JsonArray items = new JsonArray();
        for (OrderItemLine line : orderItems) {
            if (line.orderItemId() == null || line.orderItemId().isBlank()) {
                throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                        "orderItemId must not be blank in orderItems");
            }
            if (line.quantity() == null || line.quantity() <= 0) {
                throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                        "quantity must be positive for orderItemId=" + line.orderItemId());
            }
            JsonObject item = new JsonObject();
            item.addProperty("orderItemId", line.orderItemId().trim());
            item.addProperty("quantity", line.quantity());
            items.add(item);
        }
        packageDetail.add("orderItems", items);

        JsonObject body = new JsonObject();
        body.addProperty("marketplaceId", marketplaceId.trim());
        body.add("packageDetail", packageDetail);
        return body;
    }

    private void requireText(Object value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw LocalApiException.of(LocalApiException.CODE_INVALID_REQUEST,
                    field + " must not be blank");
        }
    }

    /**
     * 一行待确认发货的明细（orderItemId + quantity）。
     * <p>
     * 用 record 而不是 Map：这个形状来自官方 schema，键名拼错会被 Amazon 拒，
     * 而 400 的报错不会告诉你是哪个键写错了。强类型让拼错在编译期就暴露。
     */
    public record OrderItemLine(String orderItemId, Integer quantity) {
    }
}
