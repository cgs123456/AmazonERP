package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §S29 补齐角色门禁：豁免清单与源码逐条对齐（反射契约）。
 * <p>
 * <b>为什么反射而不信盘点表</b>：{@code tools/schema/operlog_coverage_audit.py} 第一版的
 * 注解区间只取到 {@code @PostMapping} 行，于是把 {@code @RequireRole} 写在 Mapping
 * <b>下面</b>的 {@code AgentMemoryController.scanReminders}（早已锁 ADMIN）误判成
 * 「没锁」，「38 个缺口」因此比真实数字偏高。缺陷已修并用变异验证。
 * 结论：**尺子会错，字节码里的注解不会**——所以豁免名单必须由反射钉，不能由脚本输出抄。
 * <p>
 * <b>本文件只管本模块 classpath 内的 Controller</b>。{@code amz-service-ops} 只依赖
 * {@code amz-service}，看不到 ai / user / message 模块的 Controller；那几个模块的
 * 豁免判定（{@code LoginController.verify} 这类登录态端点、{@code AgentMemoryController}
 * 的自我服务端点、{@code MessageNotifyController} 的服务间端点）留在各自模块的测试里。
 * 这里覆盖的是**跨模块共性最硬的一条**：带 {@code @InternalServiceAccess} 的写端点
 * 律不得加 {@code @RequireRole}。
 */
@DisplayName("§S29 角色门禁：服务间端点豁免 + 本模块写端点加锁")
class RoleGatingBatchContractTest {

    /**
     * 本模块要守住的写端点 Controller。新增人操作写端点必须加 {@code @RequireRole}——
     * 漏加则 {@link #moduleWriteEndpointsCarryRequireRole()} 红。
     * <p>
     * 刻意<b>不含</b>带 {@code @InternalServiceAccess} 的 Controller（本模块当前没有）：
     * 那一类由 {@link #internalServiceAccessEndpointsMustNotCarryRole()} 单独钉，
     * 混在一个断言里会让两类失败互相掩盖。
     */
    private static final List<Class<?>> GATED_CONTROLLERS = List.of(
            OpsController.class,
            ProductSelectionController.class);

    /**
     * 扫描范围：本模块全部 Controller。用于机器端点豁免断言——
     * 断言的是「凡带 {@code @InternalServiceAccess} 就不得有 {@code @RequireRole}」，
     * 与具体是哪个 Controller 无关，所以遍历全部才守得住将来新增的那一个。
     */
    /**
     * 豁免清单：{@code 方法名 → 为什么不能锁}。**单一清单，与「必须全锁」不冲突**——
     * 在清单里的端点跳过加锁断言，并额外被 {@link #exemptionsStayExempt()} 钉住
     * 「确实没有 @RequireRole」。照 {@code MultiplatformWriteEndpointGuardContractTest}
     * 的既有形状，不另起一套机制。
     *
     * <p>三条的<b>共同事实</b>：生产环境返回 0 / 写随机数，且前端刻意不提供入口
     * （决策记录 {@code amz-frontend/src/api/opsAlerts.ts:12-15}）：
     * 「放了一个只会往告警表里写随机 ASIN 的按钮，比没有按钮更糟」。
     *
     * <p><b>什么时候必须撤销这个豁免</b>：接上真实数据源、或前端放开扫描按钮之后，
     * 它们就变成真实写路径。届时删掉这里对应条目即可——
     * {@link #moduleWriteEndpointsCarryRequireRole()} 会立刻要求补锁。
     */
    private static final Map<String, String> EXEMPTED = Map.of(
            "scanReviews", "ThreadLocalRandom 造数 + 仅 mock profile 放行，前端刻意无入口"
                    + "（opsAlerts.ts:12-15），生产环境恒返回 0",
            "scanHijacks", "同上",
            "captureRanks", "同上");

    private static final List<String> WRITE_MAPPINGS = List.of(
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping");

    private static boolean isWriteEndpoint(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (WRITE_MAPPINGS.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRequireRole(Method method) {
        return method.isAnnotationPresent(RequireRole.class);
    }

    @Test
    @DisplayName("本模块人操作写端点全部带 @RequireRole")
    void moduleWriteEndpointsCarryRequireRole() {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Class<?> controller : GATED_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWriteEndpoint(method)) {
                    continue;
                }
                checked++;
                if (EXEMPTED.containsKey(method.getName())) {
                    continue;
                }
                if (!hasRequireRole(method)) {
                    missing.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        // 分母守卫：Ops 7 + ProductSelection 3 = 10，其中 3 个走豁免，实际要求加锁 7 个。
        // 数字写死是刻意的——新增端点使 checked 变化时这个断言先红，逼人回来核对豁免
        // 名单，而不是让新端点静默逃出盘点（§S20.7 的教训）。
        assertTrue(checked >= 10,
                "只扫到 " + checked + " 个写端点（期望 ≥10：Ops 7 + ProductSelection 3），反射扫描口径可能失效——"
                        + "这不是「没问题」，是「没扫到」。");
        assertTrue(missing.isEmpty(),
                "以下写端点缺 @RequireRole，VIEWER 可越权写：" + missing
                        + "。补 @RequireRole({\"OPERATOR\",\"ADMIN\"})；若坚持不加，"
                        + "请把方法与理由加进 EXEMPTED。");
    }

    /**
     * 豁免清单自身要得起追问：清单里的端点**现在**必须真的没有 {@code @RequireRole}。
     * <p>
     * 这条与上面的加锁断言是同一枚硬币的两面。少了它，有人给 scanReviews 加了锁之后，
     * EXEMPTED 里的条目就永远挂着不再有人读，下一位会以为「扫描端点仍是无管控的造数口」
     * ——而实际它早已是真实写路径。照 Multiplatform 那批的做法，豁免状态变化必须留痕。
     */
    @Test
    @DisplayName("豁免清单里的端点确实没有 @RequireRole（豁免与实现一致）")
    void exemptionsStayExempt() {
        List<String> drifted = new ArrayList<>();
        for (Class<?> controller : GATED_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                String rationale = EXEMPTED.get(method.getName());
                if (rationale == null) {
                    continue;
                }
                if (hasRequireRole(method)) {
                    drifted.add(method.getName() + " 已有 @RequireRole，应从 EXEMPTED 移除并复核理由："
                            + rationale);
                }
            }
        }
        assertTrue(drifted.isEmpty(), "豁免清单与实现不一致：" + drifted);
        assertTrue(EXEMPTED.size() == 3, "造数据点数量变了（现为 " + EXEMPTED.size()
                + "），请连豁免理由一起复核——接上真实数据源后必须撤销豁免。");
    }

    /** 兜底：本测试的扫描集合不能是空的（空集合会让上面所有断言空转通过）。 */
    @Test
    @DisplayName("母守卫：扫描集合非空")
    void scanSetsAreNotEmpty() {
        assertTrue(!GATED_CONTROLLERS.isEmpty(),
                "扫描集合为空，本测试所有断言都会空转通过——这是最危险的假绿");
        long scanned = GATED_CONTROLLERS.stream()
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(RoleGatingBatchContractTest::isWriteEndpoint)
                .count();
        assertTrue(scanned >= 10,
                "本模块只扫到 " + scanned + " 个写端点（期望 ≥10：Ops 7 + ProductSelection 3），扫描口径失效");
    }
}
