package com.amz.agent;

import com.amz.client.AdServiceClient;
import com.amz.client.InventoryServiceClient;
import com.amz.client.LogisticsServiceClient;
import com.amz.client.OrderServiceClient;
import com.amz.client.ProcurementServiceClient;
import com.amz.client.ProductServiceClient;
import com.amz.client.ReportServiceClient;
import com.amz.context.UserContext;
import com.amz.result.PageMeta;
import com.amz.result.Result;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.amz.agent.review.ReviewAnalysisResult;
import com.amz.agent.review.ReviewAnalysisService;
import com.amz.agent.review.ReviewInfo;
import com.amz.ai.knowledge.KnowledgeChunk;
import com.amz.ai.knowledge.KnowledgeSearchService;
import com.amz.agent.selection.SelectionAnalysisResult;
import com.amz.agent.selection.SelectionAnalysisService;
import com.amz.agent.selection.SelectionOpportunityInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.amz.agent.ErpToolSupport.fail;
import static com.amz.agent.ErpToolSupport.ok;
import static com.amz.agent.ErpToolSupport.isSuccess;
import static com.amz.agent.ErpToolSupport.inferLanguage;
import static com.amz.agent.ErpToolSupport.toLong;
import static com.amz.agent.ErpToolSupport.toInt;
import static com.amz.agent.ErpToolSupport.toDouble;
import static com.amz.agent.ErpToolSupport.toBool;
import static com.amz.agent.ErpToolSupport.toStr;
import static com.amz.agent.ErpToolSupport.truncate;

/**
 * ERP 运营 Agent 工具调度器。
 * 复用购物 Agent 的 Function Calling 编排架构，工具实现从"购物"改为"运营数据分析"。
 *
 * 工具清单（29 个）：
 * 基础查询 10 Tool + 分析类 7 Tool + 建议类 6 Tool + 操作类 6 Tool
 *
 * 工具 1-18 通过 Feign 调用各微服务获取真实数据；工具 19-29 调用本地 AI 服务或复合逻辑。
 * Feign 调用失败时降级返回错误信息。
 */
@Slf4j
@Component
public class ErpToolExecutor {

    @Autowired
    private ErpLogisticsTools logisticsTools;
    @Autowired
    private ErpAnalysisTools analysisTools;
    @Autowired
    private ErpOrderTools orderTools;
    @Autowired
    private ErpInventoryTools inventoryTools;
    @Autowired
    private ErpAdTools adTools;
    @Autowired
    private ErpProductTools productTools;
    @Autowired
    private ErpProcurementTools procurementTools;

    private final Gson gson = new Gson();

    /**
     * 不依赖 shopId 取数的工具（或 shopId 仅作日志、下游忽略），不参与 shopId 越权校验。
     * 其余工具凡在 args 中携带 shopId 一律强制校验其归属，防止提示注入跨店取数/写数。
     */
    private static final Set<String> TOOLS_IGNORING_SHOP_ID = Set.of(
            "estimate_fba_fees", "translate_listing", "analyze_product_reviews", "analyze_product_selection",
            "estimate_logistics_cost");

    /**
     * 需要 operate 权限的工具名集合（A-3）。
     * <p>
     * 必须与各处理方法上的 {@code @ToolPermission("agent:operate")} 标注严格一致，
     * 由 {@code ToolPermissionTest} 反射双向核对，防漂移。
     */
    static final Set<String> OPERATE_TOOLS = Set.of(
            "create_purchase_plan",
            "auto_reply_message",
            "generate_promotion_plan",
            "cross_marketplace_listing",
            "optimize_ad_campaign",
            "optimize_listing_seo",
            "optimize_shipping_route",
            "optimize_inventory_distribution");

    /**
     * 执行工具调用
     * @param call LLM 返回的函数调用描述
     * @return JSON 格式结果 {"ok":true/false,"message":"...","data":...}
     */
    public String execute(FunctionCall call) {
        if (call == null || call.getName() == null) {
            return fail("无效的工具调用");
        }
        String name = call.getName().trim();
        Map<String, Object> args = call.getArguments() == null ? Map.of() : call.getArguments();

        // 越权防护（H7）：LLM 生成的 shopId 必须属于当前登录用户授权店铺，防止提示注入诱导 Agent 跨店取数/写数。
        // TOOLS_IGNORING_SHOP_ID 中的工具不依赖 shopId（或仅作日志、下游忽略），跳过校验；其余工具凡携带 shopId 一律强制校验。
        Long argShopId = toLong(args.get("shopId"));
        if (argShopId != null && !TOOLS_IGNORING_SHOP_ID.contains(name) && !UserContext.isShopAllowed(argShopId)) {
            log.warn("Agent 工具 shopId 越权拦截 name={}, shopId={}, userId={}", name, argShopId, UserContext.getUserId());
            return fail("无权访问该店铺数据");
        }

        // 工具粒度权限（A-3）：operate 类工具要求已认证的 OPERATOR 及以上角色。
        // 任何身份/角色上下文缺失都按拒绝处理，避免异步上下文丢失导致提权。
        if (OPERATE_TOOLS.contains(name) && !hasOperatePermission()) {
            log.warn("Agent 工具权限拦截 name={}, role={}, userId={}",
                    name, UserContext.getRole(), UserContext.getUserId());
            return fail("无权执行此操作（需要 OPERATOR 及以上权限）");
        }

        // SSE 事件发布：仅当调用方（流式端点工作线程）绑定了 traceId 才生效，
        // 普通 POST / 评测链路无 trace，直接跳过，零行为变化。
        String traceId = AgentTraceContext.get();
        if (traceId != null) {
            AgentToolEventBus.publish(traceId, AgentToolEvent.call(name, truncate(String.valueOf(args), 300)));
        }
        long toolStart = System.currentTimeMillis();
        String result;
        try {
            result = switch (name) {
            case "query_orders"              -> orderTools.queryOrders(args);
            case "query_inventory"           -> inventoryTools.queryInventory(args);
            case "query_sales"               -> orderTools.querySales(args);
            case "query_profit"              -> orderTools.queryProfit(args);
            case "suggest_replenish"         -> inventoryTools.suggestReplenish(args);
            case "check_inventory_health"    -> inventoryTools.checkInventoryHealth(args);
            case "cross_marketplace_listing" -> productTools.crossMarketplaceListing(args);
            case "analyze_ad_performance"    -> adTools.analyzeAdPerformance(args);
            case "monitor_competitor_price"  -> adTools.monitorCompetitorPrice(args);
            case "estimate_fba_fees"         -> productTools.estimateFbaFees(args);
            case "translate_listing"         -> productTools.translateListing(args);
            case "generate_promotion_plan"   -> procurementTools.generatePromotionPlan(args);
            case "analyze_product_reviews"   -> analysisTools.analyzeProductReviews(args);
            case "analyze_product_selection" -> analysisTools.analyzeProductSelection(args);
            // Phase 3 新增 14 工具
            case "query_purchase_orders"      -> procurementTools.queryPurchaseOrders(args);
            case "query_suppliers"            -> procurementTools.querySuppliers(args);
            case "query_advertising"          -> adTools.queryAdvertising(args);
            case "track_shipment"            -> logisticsTools.trackShipment(args);
            case "analyze_listing_health"    -> productTools.analyzeListingHealth(args);
            case "analyze_search_terms"      -> adTools.analyzeSearchTerms(args);
            case "analyze_sales_trend"        -> analysisTools.analyzeSalesTrend(args);
            case "analyze_inventory_aging"   -> inventoryTools.analyzeInventoryAging(args);
            case "optimize_ad_campaign"      -> adTools.optimizeAdCampaign(args);
            case "optimize_listing_seo"      -> productTools.optimizeListingSeo(args);
            case "optimize_shipping_route"   -> logisticsTools.optimizeShippingRoute(args);
            case "optimize_inventory_distribution" -> logisticsTools.optimizeInventoryDistribution(args);
            case "create_purchase_plan"       -> procurementTools.createPurchasePlan(args);
            case "auto_reply_message"        -> analysisTools.autoReplyMessage(args);
            case "query_knowledge_base"      -> analysisTools.queryKnowledgeBase(args);
            default -> fail("未知工具：" + name);
            };
        } catch (RuntimeException e) {
            if (traceId != null) {
                AgentToolEventBus.publish(traceId, AgentToolEvent.result(name, false,
                        System.currentTimeMillis() - toolStart,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            throw e;
        }
        if (traceId != null) {
            AgentToolEventBus.publish(traceId, AgentToolEvent.result(name, true,
                    System.currentTimeMillis() - toolStart, truncate(result, 500)));
        }
        return result;
    }

    /**
     * 当前调用方是否有 operate 权限（包可见，供单测直接断言）。
     * userId 或 role 缺失时拒绝（fail-closed）。
     * <p>
     * 角色按**当前选定店铺**解析（{@code UserContext.getShopId()}，来自请求头）：
     * 助手执行写工具时作用的就是用户当前在看的店，用全局角色会让「店 B 是 VIEWER」
     * 的用户在店 B 里也能通过助手改数据。
     */
    static boolean hasOperatePermission() {
        Integer userId = UserContext.getUserId();
        String role = UserContext.resolveRole(UserContext.getShopId());
        return userId != null && userId > 0
                && ("OPERATOR".equals(role) || "ADMIN".equals(role));
    }


    // ===== 新增 7 Tool（P0 模块联动 + AI 智能运营） =====

    // ================================================================
    // Phase 3 新增 14 个工具（AI Agent 14→28 扩展）
    // ================================================================



    /**
     * 工具 18：物流轨迹查询。
     * 真实调用 amz-service-logistics 的 /logistics/shipment/{shipmentId}/tracking。
     * 仅传入 shipmentNo 时，先按店铺反查 shipmentId。
     */

    /**
     * 演示/估算类工具标识前缀：当前 demo 缺少这些分析所需的真实数据后端，
     * 返回值为硬编码的示例/估算，必须在对外文案中明确标注，避免被当作真实业务分析。
     */

    // ===== 工具方法 =====

    /**
     * 判断 Feign 调用返回的 Result 是否成功。
     */
    /**
     * 根据 Marketplace ID 推断目标语言。
     */
}
