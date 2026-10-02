package com.amz.service.impl;

import com.amz.mapper.OrderAuditRuleMapper;
import com.amz.mapper.OrderMapper;
import com.amz.mapper.OrderSplitLogMapper;
import com.amz.mapper.ShipmentRoutingMapper;
import com.amz.model.OrderAuditRule;
import com.amz.model.pojo.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审单规则的 fail-closed 契约。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查）</b>：规则评估的所有失败分支都折成 {@code false}
 * （正则超时、正则语法错误、正则超长、未知操作符、数值比较解析失败、字段取不到值），
 * 而 {@code false} 在调用处等价于"规则没命中"，于是 verdict 落到 {@code PASS}。
 * 后果：一条"地址黑名单 BLOCK"规则可以在批量审单高峰被整体绕过（专用线程池只有 2 个 worker，
 * 排队任务还没开跑就撞满 500ms），而日志里只有一条 warn 都算好的实现——旧代码连原因都不记。
 * 另外 {@code shipping_address} 因 Order 模型没有该字段而返回空串，
 * 意味着该类规则<b>永远</b>不可能命中：店铺以为在拦，实际一条都没拦。
 * <p>
 * 现在："无法判定"独立成第三种结果，进 {@code unevaluatedRules} 并把 verdict 抬到 REVIEW；
 * 只有真正评估过且不匹配才允许 PASS。
 */
@DisplayName("审单 fail-closed：无法判定不得伪装成 PASS")
@ExtendWith(MockitoExtension.class)
class OrderAuditFailClosedTest {

    @Mock
    private OrderAuditRuleMapper orderAuditRuleMapper;
    @Mock
    private OrderSplitLogMapper orderSplitLogMapper;
    @Mock
    private ShipmentRoutingMapper shipmentRoutingMapper;
    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderAuditServiceImpl service;

    @Test
    @DisplayName("BLOCK 规则正则语法错误：判为未评估，verdict 必须是 REVIEW 不是 PASS")
    void malformedRegexIsNotTreatedAsNoMatch() {
        stubRules(rule(1L, "shipping_address", "REGEX", "(unclosed", "BLOCK"));

        Map<String, Object> result = service.auditOrder(7L, order("US"));

        assertEquals(1, result.get("unevaluatedCount"), "语法错误的正则必须留下未评估记录");
        assertEquals("REVIEW", result.get("verdict"),
                "无法判定的 BLOCK 规则不能放行；实际 verdict=" + result.get("verdict"));
        assertNotEquals("PASS", result.get("verdict"));
    }

    @Test
    @DisplayName("正则超长（>256）：拒绝执行但仍记为未评估")
    void oversizedPatternIsRecordedNotSilentlySkipped() {
        stubRules(rule(2L, "buyer_name", "REGEX", "a".repeat(300), "BLOCK"));

        Map<String, Object> result = service.auditOrder(7L, order("US"));

        assertEquals("REVIEW", result.get("verdict"));
        assertEquals(1, result.get("unevaluatedCount"));
    }

    @Test
    @DisplayName("未知操作符：不匹配任何订单的假象必须被揭穿")
    void unknownOperatorCannotYieldPass() {
        stubRules(rule(3L, "order_status", "MATCHES_ALL", "SHIPPED", "FLAG"));

        Map<String, Object> result = service.auditOrder(7L, order("SHIPPED"));

        assertEquals("REVIEW", result.get("verdict"),
                "操作符拼错的规则等于没生效，不能报 PASS：" + result.get("unevaluatedRules"));
    }

    @Test
    @DisplayName("数值比较遇到非数字：记未评估，不折成 false")
    void nonNumericComparisonIsUnevaluated() {
        stubRules(rule(4L, "order_status", "GT", "500", "BLOCK"));

        Map<String, Object> result = service.auditOrder(7L, order("SHIPPED"));

        assertEquals("REVIEW", result.get("verdict"));
        assertEquals(1, result.get("unevaluatedCount"));
    }

    @Test
    @DisplayName("shipping_address 未建模：规则必须显式不可判定，而不是拿空串比下去")
    void addressFieldIsNotSilentlyEmpty() {
        stubRules(rule(5L, "shipping_address", "CONTAINS", "NR1 9XX", "BLOCK"));

        Map<String, Object> result = service.auditOrder(7L, order("US"));

        assertEquals("REVIEW", result.get("verdict"),
                "地址类规则在模型接入前必须报「没能力判定」，不能静默 PASS");
    }

    @Test
    @DisplayName("规则确实不匹配：这才是唯一允许 PASS 的情形")
    // 这条同时防止矫枉过正——不能把所有订单都推到 REVIEW
    void genuineNonMatchStillPasses() {
        stubRules(rule(6L, "order_status", "EQ", "CANCELED", "BLOCK"));

        Map<String, Object> result = service.auditOrder(7L, order("SHIPPED"));

        assertEquals("PASS", result.get("verdict"),
                "评估成功且不匹配才应 PASS；实际：" + result);
        assertEquals(0, result.get("unevaluatedCount"));
    }

    @Test
    @DisplayName("规则命中 BLOCK：判定与动作不受改动影响")
    void blockingRuleStillBlocks() {
        stubRules(rule(7L, "order_status", "EQ", "SHIPPED", "BLOCK"));

        Map<String, Object> result = service.auditOrder(7L, order("SHIPPED"));

        assertEquals("BLOCKED", result.get("verdict"));
        assertEquals(1, result.get("alertCount"));
        assertEquals(List.of("BLOCK"), result.get("actions"));
    }

    @Test
    @DisplayName("动作名写错：记未评估，不装作规则已生效")
    void unknownActionIsNotSilentlyIgnored() {
        stubRules(rule(8L, "order_status", "EQ", "SHIPPED", "HOLD"));

        Map<String, Object> result = service.auditOrder(7L, order("SHIPPED"));

        assertTrue(String.valueOf(result.get("unevaluatedRules")).contains("未知动作"),
                "未知动作要留下原因，实际：" + result.get("unevaluatedRules"));
    }

    @Test
    @DisplayName("SPLIT 动作只是建议：必须自报「没真的拆单」，也不写拆分日志表")
    void advisoryActionIsLabelledAsSuch() {
        stubRules(rule(9L, "order_status", "EQ", "SHIPPED", "SPLIT"));

        Map<String, Object> result = service.auditOrder(7L, order("SHIPPED"));

        assertEquals(List.of("SPLIT"), result.get("advisoryActions"));
        assertTrue(String.valueOf(result.get("advisoryNote")).contains("没有合并/拆单实现"),
                "要说清这条动作不会改数据，实际：" + result.get("advisoryNote"));
        // 命中规则本身仍要进 REVIEW，不能因为「只是建议」就当没事
        assertEquals("REVIEW", result.get("verdict"));
        // 关键：审单不产生任何拆分日志（这张表全仓零插入点）
        verifyNoInteractions(orderSplitLogMapper);
    }

    // ------------------------------------------------------------------ helpers

    private void stubRules(OrderAuditRule... rules) {
        when(orderAuditRuleMapper.selectList(any())).thenReturn(List.of(rules));
    }

    private static OrderAuditRule rule(Long id, String field, String op, String value, String action) {
        OrderAuditRule rule = new OrderAuditRule();
        rule.setId(id);
        rule.setShopId(7L);
        rule.setRuleName("rule-" + id);
        rule.setRuleType("RISK");
        rule.setConditionField(field);
        rule.setConditionOp(op);
        rule.setConditionValue(value);
        rule.setAction(action);
        rule.setPriority(0);
        rule.setEnabled(true);
        return rule;
    }

    private static Order order(String status) {
        Order order = new Order();
        order.setId(1001L);
        order.setShopId(7L);
        order.setAmazonOrderId("111-0000000-0000000");
        order.setOrderStatus(status);
        order.setBuyerName("Ada");
        order.setFulfillmentChannel("AFN");
        order.setMarketplaceId("A1P8ABTQ786FV1");
        order.setFinalPrice(new BigDecimal("42.00"));
        return order;
    }
}
