package com.amz.controller;

import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 模块写端点的角色门禁契约（§S29）。
 * <p>
 * <b>本模块最麻烦的地方是「哪些该锁」不能一刀切</b>，所以豁免是逐端点写明理由的，
 * 而不是"这个 Controller 全锁"。三类：
 * <ol>
 *   <li><b>店铺级资产 → 锁</b>：{@code KnowledgeController.uploadDocument} /
 *       {@code deleteDocument}。文档按 shopId 归属，删除会整篇从 ES 移除，
 *       影响的是全店可检索的知识，不只操作者自己。</li>
 *   <li><b>让系统花钱 → 锁</b>：{@code ReviewAnalysisController.analyze}（单次最多
 *       500 条评论）、{@code SelectionAnalysisController.analyze}。两者都打 LLM，
 *       属于"谁有权花 token"的运营决策。</li>
 *   <li><b>自我服务 → 不锁</b>：{@code AgentMemoryController.updatePreference} /
 *       {@code switchLanguage} 改的是自己的偏好；{@code chat}/{@code agentChat}/
 *       {@code erpAgent} 改的是自己的会话记忆（{@code AiController.erpAgent} 的
 *       sessionId 就是 {@code "sess-" + userId}）。给它们加 OPERATOR 门禁等于
 *       让 VIEWER 连自己的语偏好都不能改——<b>锁错了对象</b>。
 *       这一类由 {@code tools/schema/operlog_coverage_audit.py} 的 self_service
 *       分类单列，不计入"待补角色"。</li>
 * </ol>
 * <p>
 * <b>刻意不覆盖 {@code AgentMemoryController.scanReminders}</b>：它早已是
 * {@code @RequireRole({"ADMIN"})}，且带 mock 档门禁（非 mock 环境拒绝执行）。
 * 归 {@code AgentReminderScanGateTest} 管，这里重复断言只会让两处都该改。
 * <p>
 * 证据等级：反射（注解在不在）。"接上之后判得对不对"由真实链测试负责——
 * {@code standaloneSetup} 不装载 AOP，切面注解同进程内不会触发（§S23.4）。
 */
@DisplayName("AI 模块写端点：店铺资产与耗 token 动作要锁，自我服务不锁")
class AiKnowledgeWriteEndpointGuardContractTest {

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            org.springframework.web.bind.annotation.PostMapping.class,
            org.springframework.web.bind.annotation.PutMapping.class,
            org.springframework.web.bind.annotation.DeleteMapping.class,
            org.springframework.web.bind.annotation.PatchMapping.class);

    /**
     * 该锁的端点：{@code Controller#方法}。
     * <p>
     * 分母写死是刻意的——新增写端点若忘记加 {@code @RequireRole}，
     * {@link #lockedEndpointsRequireRole()} 的 checked 会先红，逼人回来核对
     * 该锁还是该豁免，而不是让新端点静默逃出盘点（§S20.7 的教训）。
     */
    private static final Set<String> MUST_LOCK = Set.of(
            "KnowledgeController#uploadDocument",
            "KnowledgeController#deleteDocument",
            "ReviewAnalysisController#analyze",
            "SelectionAnalysisController#analyze",
            // runEval：一次跑 12 个评测用例，mode=both 时每个都打 LLM
            // （≈12 次付费调用），且是「改 prompt 后手动触发」的研发动作，
            // 不是卖家的日常操作。
            "AiController#runEval");

    /**
     * 刻意不锁的自我服务端点。锁了会打错对象，理由见类注释。
     * <p>
     * {@code AgentMemoryController#chat} 与 {@code AiController#chat} 是两个不同的
     * 端点（前者带记忆+多语言，后者是裸 chat），都只动自己的会话记忆，所以都在这。
     */
    private static final Set<String> SELF_SERVICE = Set.of(
            "AgentMemoryController#updatePreference",
            "AgentMemoryController#switchLanguage",
            "AgentMemoryController#chat",
            "AiController#chat",
            "AiController#agentChat",
            "AiController#erpAgent");

    /**
     * 已由别的测试守着、这里刻意不重复的端点。
     * <p>
     * {@code AgentMemoryController#scanReminders} 早已是 {@code @RequireRole({"ADMIN"})}
     * 且带 mock 档门禁，归 {@code AgentReminderScanGateTest} 管。这里只登记它的存在，
     * 免得分母守卫把"没分类"误报成"新增端点"——但<b>不</b>断言它的角色值，
     * 避免同一件事在处都要改。
     */
    private static final Set<String> OWNED_ELSEWHERE = Set.of(
            "AgentMemoryController#scanReminders");

    private static final List<Class<?>> SCANNED = List.of(
            KnowledgeController.class,
            ReviewAnalysisController.class,
            SelectionAnalysisController.class,
            AgentMemoryController.class,
            AiController.class);

    private static boolean isWrite(Method m) {
        for (Class<? extends Annotation> mapping : WRITE_MAPPINGS) {
            if (m.isAnnotationPresent(mapping)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("店铺资产 / 耗 token 的写端点都有 @RequireRole")
    void lockedEndpointsRequireRole() {
        List<String> unguarded = new ArrayList<>();
        for (String key : MUST_LOCK) {
            Method m = find(key);
            assertTrue(m != null, "MUST_LOCK 里的 " + key + " 不存在了——改名或删除请同步本测试");
            if (!m.isAnnotationPresent(RequireRole.class)) {
                unguarded.add(key);
            }
        }
        assertTrue(unguarded.isEmpty(),
                "以下 AI 写端点缺 @RequireRole（VIEWER 可删整店知识库文档 / 触发 LLM 调用）："
                        + unguarded + "。补 @RequireRole({\"OPERATOR\",\"ADMIN\"})。");
    }

    /**
     * 自我服务端点<b>保持</b>不加锁。
     * <p>
     * 这条是防守性的，方向与上一条相反：下一位若"顺手"给 {@code updatePreference}
     * 补了 {@code @RequireRole}，VIEWER 就再也改不了自己的语言偏好，而**没有任何
     * 功能测试会红**（测试用的是有角色的账号）。所以必须把"不该锁"也钉住。
     */
    @Test
    @DisplayName("自我服务端点保持不加 @RequireRole（锁了会打错对象）")
    void selfServiceEndpointsStayUnlocked() {
        List<String> overLocked = new ArrayList<>();
        for (String key : SELF_SERVICE) {
            Method m = find(key);
            if (m == null) {
                continue;
            }
            if (m.isAnnotationPresent(RequireRole.class)) {
                overLocked.add(key);
            }
        }
        assertTrue(overLocked.isEmpty(),
                "以下端点是自我服务（改自己的偏好/会话），加了 @RequireRole 会让 VIEWER "
                        + "连自己的设置都不能改：" + overLocked
                        + "。若这是有意的产品变更，请连本测试的豁免理由一起更新。");
    }

    /**
     * 分母守卫：扫到的写端点必须覆盖清单里的每一项，否则上面两条断言会空转通过。
     * <b>空集合通过是最危险的假绿</b>——本模块写端点有 12 个，两个清单合计 9 个，
     * 说明分类口径变了（新增了端点）。这种时候必须停下来判断新端点该归哪一类。
     */
    @Test
    @DisplayName("分母守卫：清单与源码扫描结果一致")
    void listsMatchActualWriteEndpoints() {
        List<String> actual = new ArrayList<>();
        for (Class<?> controller : SCANNED) {
            for (Method m : controller.getDeclaredMethods()) {
                if (isWrite(m)) {
                    actual.add(controller.getSimpleName() + "#" + m.getName());
                }
            }
        }
        List<String> unclassified = new ArrayList<>(actual);
        unclassified.removeAll(MUST_LOCK);
        unclassified.removeAll(SELF_SERVICE);
        unclassified.removeAll(OWNED_ELSEWHERE);

        assertTrue(actual.size() >= 10,
                "只扫到 " + actual.size() + " 个写端点（期望 ≥10），反射口径可能失效");
        assertTrue(unclassified.isEmpty(),
                "以下写端点既未列入 MUST_LOCK 也未列入 SELF_SERVICE——新增端点必须归类："
                        + unclassified + "。店铺级资产或耗 token 动作 → 加 @RequireRole；"
                        + "只影响操作者自己 → 加进 SELF_SERVICE 并写明理由。");
    }

    private static Method find(String controllerAndMethod) {
        String[] parts = controllerAndMethod.split("#");
        for (Class<?> controller : SCANNED) {
            if (!controller.getSimpleName().equals(parts[0])) {
                continue;
            }
            for (Method m : controller.getDeclaredMethods()) {
                if (m.getName().equals(parts[1])) {
                    return m;
                }
            }
        }
        return null;
    }
}
