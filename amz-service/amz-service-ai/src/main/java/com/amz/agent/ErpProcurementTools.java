package com.amz.agent;

import com.amz.client.ProcurementServiceClient;
import com.amz.result.Result;
import com.amz.result.PageMeta;
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
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 采购域 Agent 工具（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>分组依据是依赖边界</b>：本族只依赖 ProcurementServiceClient，与其它族零互调用
 * （拆分前逐方法核对）。采购域工具（促销方案/订单查询/供应商/采购计划）。
 * <p>
 * 工具名与返回结构逐字保留，ErpToolExecutor 的分发改为委托调用。
 */
@Slf4j
public class ErpProcurementTools {

    private static final String DEMO_DATA_NOTE = "[演示估算·非真实业务数据] ";

    @Autowired(required = false)
    private ProcurementServiceClient procurementServiceClient;

    /**
     * 工具 12：AI 促销方案生成。
     * 通过 Feign 调用 amz-service-procurement 的 /procurement/promotion/plan 端点。
     */
    @ToolPermission("agent:operate")
    public String generatePromotionPlan(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        String goal = toStr(args.get("goal"));
        if (goal == null) goal = "提升销量";

        log.info("工具调用 generate_promotion_plan shopId={} asin={} goal={}", shopId, asin, goal);
        if (procurementServiceClient == null) {
            return fail("采购服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = procurementServiceClient.getPromotionPlan(shopId, asin, goal);
            if (!isSuccess(result)) {
                return fail("促销方案生成失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            String asinDesc = asin != null ? "ASIN " + asin + " " : "";
            return ok(String.format("已为 %s生成促销方案（目标：%s）", asinDesc, goal), data);
        } catch (Exception e) {
            log.error("generate_promotion_plan Feign 调用失败 shopId={}", shopId, e);
            return fail("采购服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 15：查询采购订单。
     * 真实调用 amz-service-procurement 的 /procurement/order/list/{shopId}。


    /**
     * 工具 16：查询供应商列表。
     * 真实调用 amz-service-procurement 的 /procurement/supplier/list/{shopId}。


    /**
     * 工具 25：最优发货路由（真实物流报价，来自 amz-service-logistics）。
     */
    /**
     * 工具 27：AI 创建采购计划（真实供应商比价，来自 amz-service-procurement）。
     * <p>
     * 当前 demo 无真实 1688 报价后端（Item2 依赖沙箱凭证），单价取确定性占位估算（非随机），
     * 避免同一输入产生不同结果；对外文案标注为演示估算。若 ProcurementServiceClient
     * compareSupplierPrices 可用则优先展示真实比价结果。
     */
    @ToolPermission("agent:operate")
    public String createPurchasePlan(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));
        Integer quantity = toInt(args.get("quantity"), 100);
        log.info("工具调用 create_purchase_plan shopId={} sku={} quantity={}", shopId, sku, quantity);
        if (procurementServiceClient == null) return fail("采购服务客户端未启用");

        String planNo = "PL" + System.currentTimeMillis();
        BigDecimal unitPrice = BigDecimal.valueOf(12.00).setScale(2, RoundingMode.HALF_UP);

        // 尝试真实供应商比价（若有数据则覆盖占位单价）
        try {
            Result<List<Map<String, Object>>> compareResult =
                    procurementServiceClient.compareSupplierPrices(shopId, sku);
            if (isSuccess(compareResult) && compareResult.getData() != null
                    && !compareResult.getData().isEmpty()) {
                Map<String, Object> best = compareResult.getData().get(0);
                Object priceObj = best.get("unitPrice");
                if (priceObj instanceof Number) {
                    unitPrice = new BigDecimal(priceObj.toString()).setScale(2, RoundingMode.HALF_UP);
                }
                String supplier = toStr(best.get("supplierName"));
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("planNo", planNo);
                plan.put("sku", sku);
                plan.put("quantity", quantity);
                plan.put("unitPrice", unitPrice);
                plan.put("totalAmount", unitPrice.multiply(BigDecimal.valueOf(quantity)));
                plan.put("status", "DRAFT");
                plan.put("bestSupplier", supplier);
                plan.put("methodNote", "单价来自真实供应商比价（compareSupplierPrices），推荐供应商 " + supplier);
                return ok(String.format("已生成采购计划 %s：%s × %d，总金额 ¥%s（真实比价，供应商：%s）",
                                planNo, sku, quantity, plan.get("totalAmount"), supplier), plan);
            }
        } catch (Exception e) {
            log.warn("供应商比价失败，回退确定性占位：{}", e.getMessage());
        }

        // 占位路径（无比价数据时）
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planNo", planNo);
        plan.put("sku", sku);
        plan.put("quantity", quantity);
        plan.put("unitPrice", unitPrice);
        plan.put("totalAmount", unitPrice.multiply(BigDecimal.valueOf(quantity)));
        plan.put("status", "DRAFT");
        plan.put("suggestion", "建议选择优质供应商（评分 A），1688 货源价格 ¥"
                + unitPrice.multiply(BigDecimal.valueOf(0.85)).setScale(2, RoundingMode.HALF_UP));
        return ok(DEMO_DATA_NOTE + String.format("已生成采购计划 %s：%s × %d，总金额 ¥%s（单价/金额为演示估算）", planNo, sku, quantity,
                        plan.get("totalAmount")), plan);
    }

    /**
     * 工具 15：查询采购订单。
     * 真实调用 amz-service-procurement 的 /procurement/order/list/{shopId}。
     */
    public String queryPurchaseOrders(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String status = toStr(args.get("status"));
        log.info("工具调用 query_purchase_orders shopId={} status={}", shopId, status);
        if (procurementServiceClient == null) {
            return fail("采购服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = procurementServiceClient.getPurchaseOrders(shopId);
            if (!isSuccess(result)) {
                return fail("采购订单查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            int count = (list == null) ? 0 : list.size();
            return ok(String.format("采购订单查询完成 shopId=%d status=%s，共 %d 条",
                            shopId, status != null ? status : "ALL", count),
                    Map.of("shopId", shopId, "status", status != null ? status : "ALL",
                            "count", count, "orders", list));
        } catch (Exception e) {
            log.error("query_purchase_orders Feign 调用失败", e);
            return fail("采购服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 工具 16：查询供应商列表。
     * 真实调用 amz-service-procurement 的 /procurement/supplier/list/{shopId}。
     */
    public String querySuppliers(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String keyword = toStr(args.get("keyword"));
        log.info("工具调用 query_suppliers shopId={} keyword={}", shopId, keyword);
        if (procurementServiceClient == null) {
            return fail("采购服务客户端未启用");
        }
        final int pageSize = 50;
        try {
            Result<List<Map<String, Object>>> result = procurementServiceClient.getSuppliers(
                    shopId, null, keyword, pageSize, null);
            if (!isSuccess(result)) {
                return fail("供应商查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            PageMeta page = result.getPage();
            if (page == null) {
                return fail("供应商分页元数据缺失，无法确认结果完整性");
            }
            List<Map<String, Object>> list = result.getData();
            if (list == null) {
                list = List.of();
            }
            boolean hasMore = page.isTruncated() || page.isHasMore();
            String nextCursor = page.getNextCursor();
            if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
                return fail("供应商分页游标缺失，无法继续查询，不能确认结果完整性");
            }
            String keywordLabel = keyword == null || keyword.isBlank() ? "ALL" : keyword;
            String message = hasMore
                    ? String.format("供应商查询完成 shopId=%d keyword=%s，本页 %d 家，hasMore=true，nextCursor=%s",
                            shopId, keywordLabel, list.size(), nextCursor)
                    : String.format("供应商查询完成 shopId=%d keyword=%s，共 %d 家", shopId, keywordLabel, list.size());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("shopId", shopId);
            data.put("keyword", keyword);
            data.put("count", list.size());
            data.put("suppliers", list);
            data.put("pageSize", page.getSize());
            data.put("returned", page.getReturned());
            data.put("hasMore", hasMore);
            data.put("truncated", page.isTruncated());
            data.put("nextCursor", nextCursor);
            data.put("total", page.getTotal());
            return ok(message, data);
        } catch (Exception e) {
            log.error("query_suppliers Feign 调用失败", e);
            return fail("采购服务暂时不可用: " + e.getMessage());
        }
    }
}
