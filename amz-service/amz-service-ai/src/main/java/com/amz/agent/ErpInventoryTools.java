package com.amz.agent;

import com.amz.client.InventoryServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.amz.agent.ErpToolSupport.fail;
import static com.amz.agent.ErpToolSupport.isSuccess;
import static com.amz.agent.ErpToolSupport.ok;
import static com.amz.agent.ErpToolSupport.toDouble;
import static com.amz.agent.ErpToolSupport.toInt;
import static com.amz.agent.ErpToolSupport.toLong;
import static com.amz.agent.ErpToolSupport.toStr;
import java.util.HashMap;

/**
 * 库存域 Agent 工具（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>分组依据是依赖边界</b>：本族只依赖 InventoryServiceClient，与其它族零互调用
 * （拆分前逐方法核对）。库存域工具（查询/补货建议/健康度/库龄）。
 * <p>
 * 工具名与返回结构逐字保留，ErpToolExecutor 的分发改为委托调用。
 */
@Slf4j
public class ErpInventoryTools {

    @Autowired(required = false)
    private InventoryServiceClient inventoryServiceClient;

    /**
     * 工具 2：查询 FBA/本地库存。
     * 通过 Feign 调用 amz-service-spapi 的 /spapi/inventory/{shopId} 端点。


    /**
     * 工具 5：智能补货建议。
     * 通过 Feign 调用 amz-service-spapi 的 /spapi/replenish/suggest 端点。
     */
    public String suggestReplenish(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));

        log.info("工具调用 suggest_replenish shopId={} sku={}", shopId, sku);
        if (inventoryServiceClient == null) {
            return fail("库存服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getReplenishSuggest(shopId, sku);
            if (!isSuccess(result)) {
                return fail("补货建议查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            if (list == null || list.isEmpty()) {
                return ok("暂无补货建议", Map.of("suggestions", List.of()));
            }
            // 取第一条作为主要建议
            Map<String, Object> first = list.get(0);
            return ok(String.format("建议补货 SKU-%s：当前库存 %s 件，建议补货 %s 件",
                            toStr(first.get("sku")),
                            first.get("currentTotalStock"),
                            first.get("suggestedReplenishQty")),
                    Map.of("suggestions", list));
        } catch (Exception e) {
            log.error("suggest_replenish Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 6：库存健康度分级（P0-1 输出）。
     * 通过 Feign 调用 amz-service-spapi 的 /spapi/inventory/health 端点。
     */
    public String checkInventoryHealth(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));

        log.info("工具调用 check_inventory_health shopId={}", shopId);
        if (inventoryServiceClient == null) {
            return fail("库存服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getInventoryHealth(shopId);
            if (!isSuccess(result)) {
                return fail("库存健康度查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            // 按健康度等级分组统计
            Map<String, Integer> distribution = new HashMap<>();
            for (Map<String, Object> item : list) {
                String status = toStr(item.get("healthStatus"));
                if (status == null) status = "UNKNOWN";
                distribution.merge(status, 1, Integer::sum);
            }
            return ok(String.format("店铺 %s 库存健康度：%s", shopId, distribution),
                    Map.of("distribution", distribution, "items", list));
        } catch (Exception e) {
            log.error("check_inventory_health Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 22：库龄分析与滞销预警（真实库存数据，库龄按可供天数代理估算）。
     */
    public String analyzeInventoryAging(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        log.info("工具调用 analyze_inventory_aging shopId={}", shopId);
        if (inventoryServiceClient == null) return fail("库存服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getInventory(shopId);
            if (!isSuccess(result)) {
                return fail("库存查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            if (list == null || list.isEmpty()) {
                return ok("暂无库存数据", Map.of("shopId", shopId, "skuCount", 0));
            }
            Map<String, Integer> healthDist = new HashMap<>();
            Map<String, Integer> ageBuckets = new LinkedHashMap<>();
            ageBuckets.put("0_30_days", 0);
            ageBuckets.put("30_90_days", 0);
            ageBuckets.put("90_180_days", 0);
            ageBuckets.put("180_365_days", 0);
            ageBuckets.put("over_365_days", 0);
            int slowMover = 0;
            for (Map<String, Object> m : list) {
                String hs = toStr(m.get("healthStatus"));
                if (hs == null) hs = "UNKNOWN";
                healthDist.merge(hs, 1, Integer::sum);
                double avail = toDouble(m.get("availableQuantity"));
                double avg30 = toDouble(m.get("avg30Days"));
                double dos = (avg30 > 0) ? avail / avg30 : -1; // 可供天数；-1 表示无近 30 日销售速度
                if (dos < 0) {
                    ageBuckets.put("over_365_days", ageBuckets.get("over_365_days") + 1);
                } else if (dos > 365) {
                    ageBuckets.put("over_365_days", ageBuckets.get("over_365_days") + 1);
                    slowMover++;
                } else if (dos > 180) {
                    ageBuckets.put("180_365_days", ageBuckets.get("180_365_days") + 1);
                    slowMover++;
                } else if (dos > 90) {
                    ageBuckets.put("90_180_days", ageBuckets.get("90_180_days") + 1);
                } else if (dos > 30) {
                    ageBuckets.put("30_90_days", ageBuckets.get("30_90_days") + 1);
                } else {
                    ageBuckets.put("0_30_days", ageBuckets.get("0_30_days") + 1);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shopId", shopId);
            out.put("skuCount", list.size());
            out.put("healthDistribution", healthDist);
            out.put("ageBucketsByDaysOfSupply", ageBuckets);
            out.put("slowMoverCount", slowMover);
            out.put("methodNote", "库龄按可供天数(availableQuantity/近30日日均销量)估算，非真实入库日期；无销售速度的 SKU 归入 over_365_days");
            return ok(String.format("库龄分析（基于可供天数估算）：%d 个 SKU，滞销(可供>180天) %d 个（真实库存数据）",
                            list.size(), slowMover), out);
        } catch (Exception e) {
            log.error("analyze_inventory_aging Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 2：查询 FBA/本地库存。
     * 通过 Feign 调用 amz-service-spapi 的 /spapi/inventory/{shopId} 端点。
     */
    public String queryInventory(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));

        log.info("工具调用 query_inventory shopId={} sku={}", shopId, sku);
        if (inventoryServiceClient == null) {
            return fail("库存服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = inventoryServiceClient.getInventory(shopId);
            if (!isSuccess(result)) {
                return fail("库存查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            // 按 sku 过滤
            if (sku != null && !sku.isBlank()) {
                list = list.stream()
                        .filter(m -> sku.equals(toStr(m.get("sku"))))
                        .toList();
            }
            int totalAvailable = list.stream()
                    .mapToInt(m -> toInt(m.get("availableQuantity"), 0))
                    .sum();
            return ok(String.format("查询到 %d 个 SKU，可售库存共 %d 件", list.size(), totalAvailable),
                    Map.of("skuCount", list.size(), "totalAvailable", totalAvailable, "items", list));
        } catch (Exception e) {
            log.error("query_inventory Feign 调用失败 shopId={}", shopId, e);
            return fail("库存服务暂时不可用: " + e.getMessage());
        }
    }
}
