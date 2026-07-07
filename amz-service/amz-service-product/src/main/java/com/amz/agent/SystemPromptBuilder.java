package com.amz.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构建 Agent System Prompt：
 * 1. 工具说明（5 个工具的签名与用途）
 * 2. 输出格式约束（JSON Schema 式描述）
 * 3. Few-Shot 示例（从 FewShotStore 读取最近 3 条成功样本）
 */
@Component
@Slf4j
public class SystemPromptBuilder {

    @Autowired
    private FewShotStore fewShotStore;

    /** 注入的 Few-Shot 示例数量 */
    private static final int FEWSHOT_LIMIT = 3;

    private static final String TOOL_DESCRIPTION = """
            你是红书商城购物助手。你可以使用以下工具帮用户完成购物：
            - search_products(keyword)：搜索商品。keyword 为搜索关键词。
            - get_product_detail(productId)：查看商品详情。productId 为商品 ID（整数）。
            - add_to_cart(productId, attributes)：将商品加入购物车。productId 为商品 ID；attributes 为可选的商品属性 JSON，如 {"颜色":"红色"}。
            - create_order(productId, attributes)：立即下单（会扣减库存）。productId 为商品 ID；attributes 为可选的商品属性 JSON。
            - check_coupons()：查询当前用户可用的优惠券。无需参数。

            规则：
            1. 每次只能调用一个工具，完成后我会告诉你工具执行结果，你再决定下一步。
            2. 当你需要调用工具时，只输出一个 JSON 对象，格式如下（不要输出其他内容）：
               ```json
               {"name":"工具名","arguments":{"参数名":"参数值"}}
               ```
            3. 当你已经获得足够信息可以回答用户时，用自然语言回复（不要输出 JSON）。
            4. 如果用户的请求不明确（例如"帮我买那个红色的"但没指定商品），先调用 search_products 搜索。
            """;

    /**
     * 构建完整 system prompt（工具说明 + Few-Shot 示例）
     */
    public String build() {
        StringBuilder sb = new StringBuilder(TOOL_DESCRIPTION);

        // 注入 Few-Shot 示例
        List<String> samples = fewShotStore.getSuccessSamples(FEWSHOT_LIMIT);
        if (!samples.isEmpty()) {
            sb.append("\n\n以下是正确的工具调用示例，请参考其格式：\n");
            for (int i = 0; i < samples.size(); i++) {
                sb.append("示例 ").append(i + 1).append("：\n").append(samples.get(i)).append("\n");
            }
        }

        return sb.toString();
    }
}
