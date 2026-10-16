package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API 手动触发类写端点的角色门禁契约（§S29）。
 * <p>
 * <b>锁的是「手动触发」而不是「同步本身」</b>：{@code InventoryController.sync} 与
 * {@code ReplenishmentController.calc} 调的是同一批 scheduler 方法（定时任务也在用），
 * 加的是「谁能让系统现在就去打 SP-API」这一层。整店库存同步会打满 SP-API 配额并全量
 * 落库，补货重算按店铺扫全量库存——都是有成本的运营动作，VIEWER 不应能触发。
 * <p>
 * <b>本测试的另一半是「双信任」不变式</b>，而那一条的成文经历了一次自我纠正：
 * 最初我断言「服务间端点不得加 {@code @RequireRole}」，理由是对 {@code /internal/**}
 * 剥 JWT、role=null 会被拦——跑出来 5 个「违规」，而它们全是在线调用路径。
 * 读 {@code RequireRoleAspect} 原文后确认：**白名单服务身份本来就放行**，
 * 由端点 {@code @InternalServiceAccess} 承担服务级授权。所以正确的问题是
 * 「角色门禁有没有少」，不是「有没有多」。详见断言方法上的注释。
 */
@DisplayName("SP-API 手动同步端点：该锁的锁，双信任端点的角色门禁不缺失")
class SpapiManualSyncGuardContractTest {

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            org.springframework.web.bind.annotation.PostMapping.class,
            org.springframework.web.bind.annotation.PutMapping.class,
            org.springframework.web.bind.annotation.DeleteMapping.class,
            org.springframework.web.bind.annotation.PatchMapping.class);

    /** 本批加锁的两个「手动触发」端点。分母写死，新增端点忘记加注解时断言先红。 */
    private static final int EXPECTED_MANUAL_TRIGGERS = 2;

    /** 本模块全部 Controller——服务间豁免断言要覆盖新增的那一个，不能只盯已知的两个。 */
    private static final List<Class<?>> MODULE_CONTROLLERS = List.of(
            InventoryController.class,
            ReplenishmentController.class,
            FeedsController.class,
            FinancialDataController.class,
            ReportsController.class,
            ShipmentConfirmationController.class,
            SpapiController.class,
            ConnectorController.class);

    @Test
    @DisplayName("手动触发端点（sync / calc）都有 @RequireRole")
    void manualTriggersRequireRole() {
        List<String> unguarded = new ArrayList<>();
        int checked = 0;
        for (Class<?> controller : List.of(InventoryController.class, ReplenishmentController.class)) {
            for (Method m : controller.getDeclaredMethods()) {
                if (writeMappings(m).isEmpty()) {
                    continue;
                }
                checked++;
                if (!m.isAnnotationPresent(RequireRole.class)) {
                    unguarded.add(controller.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertTrue(checked == EXPECTED_MANUAL_TRIGGERS,
                "应扫到 " + EXPECTED_MANUAL_TRIGGERS + " 个手动触发端点，实际 " + checked
                        + "——新增端点必须同步补 @RequireRole 并更新本数字");
        assertTrue(unguarded.isEmpty(),
                "以下手动触发端点缺 @RequireRole（VIEWER 可打满 SP-API 配额）：" + unguarded
                        + "。补 @RequireRole({\"OPERATOR\",\"ADMIN\"})。");
    }

    /**
     * 服务间端点是**双信任**，不是「必须豁免角色」。
     * <p>
     * <b>这条断言曾写错过一次，改错了方向</b>（§S29 自查纠正）：第一版断言
     * 「带 {@code @InternalServiceAccess} 的写端点<b>不得</b>加 {@code @RequireRole}」，
     * 理由是 {@code FeignAuthRelayConfig} 对 {@code /internal/**} 剥掉用户 JWT、
     * {@code RequireRoleAspect} 见 role=null 一律拦截，于是加了锁会打死调用方。
     * 跑出来 5 个违规——而它们<b>全是在线的正常调用路径</b>（Feign 从 product /
     * logistics / order / finance 调进来）。错在我没读 {@code RequireRoleAspect} 原文：
     * 它明确写了「已由 BaseAuthInterceptor 校验并注入的<b>白名单服务身份 → 放行</b>，
     * 由端点 @InternalServiceAccess 承担服务级授权」。
     * <p>
     * 所以真正的语义是：{@code @InternalServiceAccess} 管<b>服务</b>调用方放行，
     * {@code @RequireRole} 管<b>人</b>调用方——两者叠加成双信任，不是互斥。
     * 本断言钉的是这一点：这类端点<b>可以</b>带角色门禁，且角色门禁不能少
     * （少了就意味着任何拿到用户 JWT 的调用方都能触发整店 SP-API 同步）。
     */
    @Test
    @DisplayName("双信任端点：@InternalServiceAccess 与 @RequireRole 可以叠加，且角色门禁不缺失")
    void internalServiceAccessEndpointsKeepDualTrust() {
        List<String> missingRole = new ArrayList<>();
        int internalWrites = 0;
        for (Class<?> controller : MODULE_CONTROLLERS) {
            for (Method m : controller.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(InternalServiceAccess.class)
                        || writeMappings(m).isEmpty()) {
                    continue;
                }
                internalWrites++;
                if (!m.isAnnotationPresent(RequireRole.class)) {
                    missingRole.add(controller.getSimpleName() + "#" + m.getName());
                }
            }
        }
        // 分母守卫：本模块确实存在服务间写端点。若哪天扫到 0 个，
        // 说明这条断言已空转，须回头确认是端点改名还是真被删了。
        assertTrue(internalWrites > 0,
                "本模块一个 @InternalServiceAccess 写端点都没扫到——断言已空转，"
                        + "请确认是端点改名还是扫描集合失效");
        assertTrue(missingRole.isEmpty(),
                "以下服务间写端点缺 @RequireRole：任何持有用户 JWT 的调用方都能触发它"
                        + "（服务调用方由 @InternalServiceAccess 放行，人调用方靠角色门禁拦）："
                        + missingRole);
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
