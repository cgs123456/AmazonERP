package com.amz.controller;

import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单审计写端点的角色门禁契约（§S29）。
 * <p>
 * <b>动因</b>：审单规则决定「哪些订单被拦、哪些自动放行」，直接改变下单流向；
 * 审单执行会写拆单/路由日志。这 6 个写端点此前**一个 {@code @RequireRole} 都没有**，
 * VIEWER（语义=只读）可以改规则、可以触发全店批量审单。
 * <p>
 * <b>不豁免任何一个</b>：与 ops 的「造数据点」、AI 的「自我服务」不同，
 * 这里的每个端点都写的是**店铺级**决策，没有「只影响自己」之分。
 * <ul>
 *   <li>{@code createRule}/{@code updateRule}/{@code deleteRule}/{@code toggleRule}：
 *       规则是店铺主数据，改一条就改变后续每一个订单的命运；</li>
 *   <li>{@code auditOrder}/{@code batchAudit}：触发审单会写拆单与路由日志，
 *       {@code batchAudit} 一次扫全店订单。</li>
 * </ul>
 * <p>
 * <b>没有使用 {@code POST /order/saveOrder}</b>（B2C 手工下单）的原因：
 * 那个端点是**自我服务**——{@code OrderServiceImpl.saveOrder} 强制取
 * {@code UserContext.getUserId()}、不认 body 里的 userId（原实现认，于是任何登录者
 * 都能给任意用户造订单）。给它加 OPERATOR 门禁会让 VIEWER 连自己下单都不行，
 * 而 {@code GET /order/getOrderList} 读的是认证上下文、没有门禁，两口径会反。
 * 它由 {@code tools/schema/operlog_coverage_audit.py} 的 self_service 分类单列，
 * 不计入「待补角色」。
 * <p>
 * 证据等级：反射（注解在不在）。「接上之后判得对不对」由真实链测试负责——
 * {@code standaloneSetup} 不装载 AOP，切面注解同进程内不会触发（§S23.4）。
 */
@DisplayName("订单审单写端点：全部要求 OPERATOR/ADMIN")
class OrderAuditWriteEndpointGuardContractTest {

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            org.springframework.web.bind.annotation.PostMapping.class,
            org.springframework.web.bind.annotation.PutMapping.class,
            org.springframework.web.bind.annotation.DeleteMapping.class,
            org.springframework.web.bind.annotation.PatchMapping.class);

    /** 本 Controller 的写端点总数。分母写死：新增端点忘记加注解时断言先红。 */
    private static final int EXPECTED_WRITE_ENDPOINTS = 6;

    @Test
    @DisplayName("每个写端点都有 @RequireRole")
    void everyWriteEndpointRequiresRole() {
        List<String> unguarded = new ArrayList<>();
        int checked = 0;
        for (Method m : OrderAuditController.class.getDeclaredMethods()) {
            if (writeMappings(m).isEmpty()) {
                continue;
            }
            checked++;
            if (!m.isAnnotationPresent(RequireRole.class)) {
                unguarded.add(m.getName() + " " + writeMappings(m));
            }
        }
        // 分母守卫：扫到 0 个说明反射口径失效，那不是"没问题"
        assertTrue(checked == EXPECTED_WRITE_ENDPOINTS,
                "OrderAuditController 应扫到 " + EXPECTED_WRITE_ENDPOINTS
                        + " 个写端点，实际 " + checked + "——反射口径可能失效，"
                        + "或新增了端点（新增必须同步补 @RequireRole 并更新本数字）");
        assertTrue(unguarded.isEmpty(),
                "OrderAuditController 存在未声明 @RequireRole 的写端点（VIEWER 可改审单规则）："
                        + unguarded + "。补 @RequireRole({\"OPERATOR\",\"ADMIN\"})。");
    }

    private List<String> writeMappings(Method m) {
        List<String> verbs = new ArrayList<>();
        for (Class<? extends Annotation> mapping : WRITE_MAPPINGS) {
            if (m.isAnnotationPresent(mapping)) {
                verbs.add(mapping.getSimpleName().replace("Mapping", ""));
            }
        }
        return verbs;
    }
}
