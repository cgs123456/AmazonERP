package com.amz.agent.eval;

import java.util.List;

/**
 * Agent 标准评测用例集。
 * <p>
 * 覆盖全部 12 个工具的真实运营场景，每次修改 prompt 后自动跑回归。
 * <p>
 * 评测标准：
 * - 通过条件：Agent 回复包含所有 expectedKeywords（不区分大小写）
 * - 工具选择：expectedToolName 记录期望调用的工具（供日志分析，当前不强制校验）
 */
public class AgentEvalCases {

    /**
     * 返回全部标准评测用例（12 个，覆盖全部工具）。
     */
    public static List<AgentEvalCase> all() {
        return List.of(
                // ===== 基础 5 工具 =====
                AgentEvalCase.builder()
                        .id("EVAL-001")
                        .description("订单查询 - 最近7天订单汇总")
                        .category("ORDER")
                        .question("最近7天订单情况如何？店铺ID为1")
                        .expectedToolName("query_orders")
                        .expectedKeywords(List.of("订单", "23"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-002")
                        .description("库存查询 - 指定SKU库存")
                        .category("INVENTORY")
                        .question("SKU-001的库存够吗？店铺ID为1")
                        .expectedToolName("query_inventory")
                        .expectedKeywords(List.of("库存", "FBA"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-003")
                        .description("销售额趋势查询")
                        .category("SALES")
                        .question("店铺1最近7天的销售额趋势怎么样？")
                        .expectedToolName("query_sales")
                        .expectedKeywords(List.of("销售", "增长"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-004")
                        .description("单品利润分析")
                        .category("PROFIT")
                        .question("ASIN B08X4的利润怎么样？店铺ID为1")
                        .expectedToolName("query_profit")
                        .expectedKeywords(List.of("利润", "成本"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-005")
                        .description("智能补货建议")
                        .category("INVENTORY")
                        .question("SKU-001需要补货吗？店铺ID为1")
                        .expectedToolName("suggest_replenish")
                        .expectedKeywords(List.of("补货", "库存"))
                        .build(),

                // ===== 扩展 7 工具 =====
                AgentEvalCase.builder()
                        .id("EVAL-006")
                        .description("库存健康度分级")
                        .category("INVENTORY")
                        .question("店铺1的库存健康度如何？有哪些紧急SKU？")
                        .expectedToolName("check_inventory_health")
                        .expectedKeywords(List.of("库存", "紧急"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-007")
                        .description("跨站点Listing复制")
                        .category("LISTING")
                        .question("把ASIN B08X4复制到德国站，店铺ID为1")
                        .expectedToolName("cross_marketplace_listing")
                        .expectedKeywords(List.of("复制", "德国"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-008")
                        .description("广告ACoS分析")
                        .category("AD")
                        .question("ASIN B08X4最近14天广告表现如何？店铺ID为1")
                        .expectedToolName("analyze_ad_performance")
                        .expectedKeywords(List.of("广告", "ACoS"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-009")
                        .description("竞品价格监控")
                        .category("PRICING")
                        .question("ASIN B08X4竞品价格是多少？店铺ID为1")
                        .expectedToolName("monitor_competitor_price")
                        .expectedKeywords(List.of("竞品", "Buy Box"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-010")
                        .description("FBA费用预估")
                        .category("FEES")
                        .question("SKU-001（0.5kg，standard）的FBA费用多少？店铺ID为1")
                        .expectedToolName("estimate_fba_fees")
                        .expectedKeywords(List.of("FBA", "费用"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-011")
                        .description("多语种翻译")
                        .category("TRANSLATION")
                        .question("把\"Wireless Bluetooth Headphones\"翻译成德语，店铺ID为1")
                        .expectedToolName("translate_listing")
                        .expectedKeywords(List.of("翻译", "德"))
                        .build(),

                AgentEvalCase.builder()
                        .id("EVAL-012")
                        .description("AI促销方案生成")
                        .category("PROMOTION")
                        .question("为ASIN B08X4生成一个促销方案，目标是清库存，店铺ID为1")
                        .expectedToolName("generate_promotion_plan")
                        .expectedKeywords(List.of("促销", "清库存"))
                        .build()
        );
    }
}
