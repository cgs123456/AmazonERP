package com.amz.agent;

import com.amz.client.LogisticsServiceClient;
import com.amz.result.PageMeta;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.amz.agent.ErpToolSupport.fail;
import static com.amz.agent.ErpToolSupport.isSuccess;
import static com.amz.agent.ErpToolSupport.ok;
import static com.amz.agent.ErpToolSupport.toDouble;
import static com.amz.agent.ErpToolSupport.toInt;
import static com.amz.agent.ErpToolSupport.toLong;
import static com.amz.agent.ErpToolSupport.toStr;

/**
 * 物流相关 Agent 工具（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>分组依据是依赖边界</b>：这一族只依赖 {@code LogisticsServiceClient}，
 * 与订单/广告/商品等其它工具零互调用（拆分前逐方法核对过）。按「各自只持有一个
 * Feign 客户端」切，新增物流工具时不必碰别的类。
 * <p>
 * <b>工具名与行为逐字保留</b>：{@code track_shipment} /
 * {@code optimize_shipping_route} / {@code optimize_inventory_distribution}
 * 的分发改为委托调用，对 LLM 与 SSE 事件链路零可见变化。
 */
@Slf4j
public class ErpLogisticsTools {

    @Autowired(required = false)
    private LogisticsServiceClient logisticsServiceClient;

    private static final int SHIPMENT_LOOKUP_PAGE_SIZE = 200;
    private static final int SHIPMENT_LOOKUP_MAX_PAGES = 10;
    private static final int TRACKING_PAGE_SIZE = 200;
    private static final int TRACKING_MAX_PAGES = 10;

    public String trackShipment(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String shipmentNo = toStr(args.get("shipmentNo"));
        Long shipmentId = toLong(args.get("shipmentId"));
        log.info("工具调用 track_shipment shopId={} shipmentNo={} shipmentId={}", shopId, shipmentNo, shipmentId);

        if (logisticsServiceClient == null) {
            return fail("物流客户端未启用");
        }
        try {
            // 仅提供 shipmentNo 时，先按店铺有界翻页反查 shipmentId。
            // 只扫描前 N 页；若仍截断，必须返回“未取全”，不能误报“未找到”。
            if (shipmentId == null && shipmentNo != null && !shipmentNo.isBlank()) {
                ShipmentLookup lookup = findShipmentByNo(shopId, shipmentNo);
                if (lookup.error() != null) {
                    return fail(lookup.error());
                }
                shipmentId = lookup.shipmentId();
            }
            if (shipmentId == null) {
                return fail("未找到对应的物流货件（shipmentId / shipmentNo 无效）");
            }
            TrackingScan scan = findTrackingTimeline(shipmentId);
            if (scan.error() != null) {
                return fail(scan.error());
            }
            List<Map<String, Object>> tracking = scan.events();
            int count = tracking.size();
            if (count == 0) {
                return ok(String.format("货件 %s 暂无轨迹记录", shipmentId),
                        Map.of("shipmentId", shipmentId, "tracking", List.of()));
            }
            Map<String, Object> latest = tracking.get(count - 1);
            return ok(String.format("物流货件 %s：最新轨迹【%s】%s，共 %d 条",
                            shipmentId, latest.get("location"), latest.get("description"), count),
                    Map.of("shipmentId", shipmentId, "tracking", tracking));
        } catch (Exception e) {
            log.error("track_shipment Feign 调用失败", e);
            return fail("物流服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 有界读取轨迹时间线，只有翻到最后一页才允许判定“最新轨迹”。
     * <p>
     * 返回 {@code error != null} 表示结果不完整或查询失败，调用方不得据此推断最新状态。
     */
    private TrackingScan findTrackingTimeline(Long shipmentId) {
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        for (int pageNo = 0; pageNo < TRACKING_MAX_PAGES; pageNo++) {
            Result<List<Map<String, Object>>> result = logisticsServiceClient.getTracking(
                    shipmentId, TRACKING_PAGE_SIZE, cursor);
            if (!isSuccess(result)) {
                return new TrackingScan(events,
                        "物流轨迹查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> rows = result.getData();
            if (rows != null) {
                events.addAll(rows);
            }

            PageMeta page = result.getPage();
            if (page == null) {
                return new TrackingScan(events,
                        "物流轨迹分页元数据缺失，无法确认结果完整性，不能确认最新轨迹");
            }
            boolean hasMore = page.isTruncated() || page.isHasMore();
            if (!hasMore) {
                return new TrackingScan(events, null);
            }
            String nextCursor = page.getNextCursor();
            if (nextCursor == null || nextCursor.isBlank()) {
                return new TrackingScan(events,
                        "物流轨迹分页游标缺失，无法继续查询，不能确认最新轨迹");
            }
            if (!seenCursors.add(nextCursor)) {
                return new TrackingScan(events,
                        "物流轨迹分页游标重复，已停止查询，不能确认最新轨迹");
            }
            cursor = nextCursor;
        }
        return new TrackingScan(events, String.format(
                "物流轨迹扫描超过 %d 页（每页 %d 条），结果未取全，不能确认最新轨迹",
                TRACKING_MAX_PAGES, TRACKING_PAGE_SIZE));
    }

    /** 轨迹扫描结果：完整结束时 error 为 null，否则不得把已取片段当作全量。 */
    private record TrackingScan(List<Map<String, Object>> events, String error) {
    }

    /**
     * 按 shipmentNo 在店铺货件列表中做有界游标翻页。
     * <p>
     * 返回 {@code error != null} 表示无法完成完整查询；返回 {@code shipmentId == null}
     * 且 {@code error == null} 才表示已经翻完所有页且确实不存在该货件。
     */
    private ShipmentLookup findShipmentByNo(Long shopId, String shipmentNo) {
        String cursor = null;
        Set<String> seenCursors = new HashSet<>();
        for (int pageNo = 0; pageNo < SHIPMENT_LOOKUP_MAX_PAGES; pageNo++) {
            Result<List<Map<String, Object>>> listResult = logisticsServiceClient.listShipments(
                    shopId, null, SHIPMENT_LOOKUP_PAGE_SIZE, cursor);
            if (!isSuccess(listResult)) {
                return new ShipmentLookup(null,
                        "货件列表查询失败: " + (listResult != null ? listResult.getMessage() : "无响应"));
            }
            for (Map<String, Object> shipment : listResult.getData()) {
                if (shipment != null && shipmentNo.equals(toStr(shipment.get("shipmentNo")))) {
                    Long id = toLong(shipment.get("id"));
                    if (id != null) {
                        return new ShipmentLookup(id, null);
                    }
                }
            }

            PageMeta page = listResult.getPage();
            if (page == null) {
                return new ShipmentLookup(null,
                        "货件列表分页元数据缺失，无法确认结果完整性，不能判定 shipmentNo 不存在");
            }
            boolean hasMore = page.isTruncated() || page.isHasMore();
            if (!hasMore) {
                return new ShipmentLookup(null, null);
            }
            String nextCursor = page.getNextCursor();
            if (nextCursor == null || nextCursor.isBlank()) {
                return new ShipmentLookup(null,
                        "货件列表分页游标缺失，无法继续反查，不能判定 shipmentNo 不存在");
            }
            if (!seenCursors.add(nextCursor)) {
                return new ShipmentLookup(null,
                        "货件列表分页游标重复，已停止反查，不能判定 shipmentNo 不存在");
            }
            cursor = nextCursor;
        }
        return new ShipmentLookup(null, String.format(
                "货件列表扫描超过 %d 页（每页 %d 条），结果未取全，不能确认 shipmentNo 不存在",
                SHIPMENT_LOOKUP_MAX_PAGES, SHIPMENT_LOOKUP_PAGE_SIZE));
    }

    /** 货件号反查结果：要么拿到 id，要么携带不可忽略的查询错误。 */
    private record ShipmentLookup(Long shipmentId, String error) {
    }

    @ToolPermission("agent:operate")
    public String optimizeShippingRoute(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String orderId = toStr(args.get("orderId"));
        log.info("工具调用 optimize_shipping_route shopId={} orderId={}", shopId, orderId);
        if (logisticsServiceClient == null) return fail("物流服务客户端未启用");
        try {
            Result<Map<String, Object>> result =
                    logisticsServiceClient.compareShippingQuotes(shopId, "CN", "US", null, null);
            if (!isSuccess(result) || result.getData() == null) {
                return fail("物流报价查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            List<Map<String, Object>> options = new ArrayList<>();
            Object rawOptions = data.get("options");
            if (rawOptions instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> opts = new ArrayList<>((List<Map<String, Object>>) rawOptions);
                opts.sort((a, b) -> Double.compare(toDouble(a.get("cost")), toDouble(b.get("cost"))));
                options = opts;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", orderId);
            out.put("recommended", options.isEmpty() ? null : options.get(0));
            out.put("alternatives", options.size() > 1 ? options.subList(1, Math.min(3, options.size())) : List.of());
            out.put("methodNote", "路由由真实物流报价 API 生成（amz-service-logistics compareShippingQuotes）");
            Map<String, Object> rec = options.isEmpty() ? null : options.get(0);
            String msg = rec != null
                    ? String.format("订单 %s 最优路由：仓库 %s，费用 $%.2f，时效 %s（真实报价）",
                            orderId, rec.get("warehouse"), toDouble(rec.get("cost")), rec.get("transitDays"))
                    : String.format("订单 %s 路由：暂无可用报价（shopId=%s）", orderId, shopId);
            return ok(msg, out);
        } catch (Exception e) {
            log.error("optimize_shipping_route Feign 调用失败 shopId={}", shopId, e);
            return fail("物流服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 26：库存跨仓调拨建议（真实多仓视图，来自 amz-service-logistics）。
     */
    @ToolPermission("agent:operate")
    public String optimizeInventoryDistribution(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        log.info("工具调用 optimize_inventory_distribution shopId={}", shopId);
        if (logisticsServiceClient == null) return fail("物流服务客户端未启用");
        try {
            Result<Map<String, Object>> result = logisticsServiceClient.getGlobalInventoryView(shopId);
            if (!isSuccess(result) || result.getData() == null) {
                return fail("多仓库存视图查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shopId", shopId);
            out.put("inventoryView", data);
            out.put("methodNote", "调拨建议基于真实多仓库存视图（amz-service-logistics getGlobalInventoryView），"
                    + "具体调拨决策需结合各仓订单量与补货周期人工复核");
            int totalSku = toInt(data.get("totalSkuCount"), 0);
            int lowStock = toInt(data.get("lowStockSkuCount"), 0);
            String msg = String.format("跨仓调拨视图：%d 个 SKU，低库存预警 %d 个（真实多仓库存数据）", totalSku, lowStock);
            return ok(msg, out);
        } catch (Exception e) {
            log.error("optimize_inventory_distribution Feign 调用失败 shopId={}", shopId, e);
            return fail("物流服务暂时不可用: " + e.getMessage());
        }
    }

}

