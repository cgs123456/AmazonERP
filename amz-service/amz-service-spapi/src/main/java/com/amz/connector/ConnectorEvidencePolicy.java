package com.amz.connector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器证据门禁（spec §1.9.1）：把用户口径「暂时没有对接 API，但有 API 就能直接用」
 * 变成<b>机器可判定</b>的等级，而不是人工声明。
 * <p>
 * <b>问题：</b>「有 API 就能直接用」这种承诺天然不可证伪——没有等级定义时，
 * 「类名叫 {@code *RealClient}」「写了 {@code @Profile("!mock")}」「开发者说有对接能力」
 * 都会被当成证据（spec §1.9.1(1) 的 E0 明确排除这三种）。
 * <p>
 * <b>解决：</b>两级判定 + 一张静态要求表：
 * <ul>
 *   <li><b>等级</b> {@link Level}：E0 无证据 / E1 自证 / E2 桩回放 / E3 契约锁定 /
 *       E4 沙箱联调 / E5 生产联调；E1 只能证明「实现与测试同源」，**不能**作为 A1 通过依据；</li>
 *   <li><b>标准</b> {@link Criterion}：spec §1.9.1(2) 的 A1–A8，每条给出
 *       「无凭证阶段上限」{@link Criterion#offlineCeiling()} 与「凭证到位后必须补证」
 *       {@link Criterion#requiredLevel()}；</li>
 *   <li><b>判定</b> {@link #evaluate(Collection)}：整体等级 = A1–A8 的<b>最弱一环</b>（取最小值），
 *       {@code apiReady} = 每条都达到 {@code requiredLevel}，{@code reachable} = A5 达 E4
 *       （真实联调记录是唯一取证路径）。</li>
 * </ul>
 * <p>
 * <b>对外口径（硬约束）：</b>{@link Assessment#displayText()} 只有三种输出——
 * 未达 E4 时只能说「具备对接能力（未联调）」，**不得**说「已接通」或「有 API 即可直接使用」；
 * 仅有联调但未补齐全部标准时是「已接通（联调中）」；全部达标才是「API-Ready（已联调）」。
 * Task 6 的能力清单端点 {@code GET /api/connectors} 用本类的 {@code evidenceLevel} 字段对外输出。
 */
public final class ConnectorEvidencePolicy {

    private ConnectorEvidencePolicy() {
    }

    /**
     * 证据等级（spec §1.9.1(1)）。{@link #rank()} 用于比较，{@link #atLeast(Level)} 为唯一比较入口。
     */
    public enum Level {
        /** 无证据：类名、注解、人工声明都不算。 */
        E0("无证据", 0),
        /** 自证：测试内重写同一公式/假设后比对，只能证明与测试同源。 */
        E1("自证", 1),
        /** 桩回放：本地桩按官方模型返回样例，断言路径/查询/头/分页/错误码。 */
        E2("桩回放", 2),
        /** 契约锁定：官方模型快照 + 哈希锁 + 已知答案测试（KAT）。无凭证阶段的最高等级。 */
        E3("契约锁定", 3),
        /** 沙箱联调：真实凭证打通平台沙箱或最小只读 operation。 */
        E4("沙箱联调", 4),
        /** 生产联调：生产凭证 + 真实店铺 + 覆盖 429/限流头/文档下载。 */
        E5("生产联调", 5);

        private final String label;
        private final int rank;

        Level(String label, int rank) {
            this.label = label;
            this.rank = rank;
        }

        public String label() {
            return label;
        }

        public int rank() {
            return rank;
        }

        /** 本等级是否不低于 {@code other}（null 视为 E0）。 */
        public boolean atLeast(Level other) {
            return rank >= (other == null ? 0 : other.rank);
        }

        /** 两个等级中较低者（null 视为 E0）。 */
        public static Level weaker(Level a, Level b) {
            Level left = a == null ? E0 : a;
            Level right = b == null ? E0 : b;
            return left.rank <= right.rank ? left : right;
        }

        /** 宽松解析（{@code "E3"} / {@code "e3"} / 空白）；无法识别时抛 {@link IllegalArgumentException}。 */
        public static Level parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("证据等级不能为空");
            }
            try {
                return Level.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知证据等级：\"" + value + "\"（合法值 E0..E5）", e);
            }
        }
    }

    /**
     * 取证标准（spec §1.9.1(2) 的 A1–A8）。
     * <p>
     * {@code offlineCeiling} 是<b>无凭证阶段的等级上限</b>（A5 只能到 E1，因为联调记录无法伪造）；
     * {@code requiredLevel} 是「可以宣称 API-Ready」时该条必须达到的等级。
     */
    public enum Criterion {
        /** 认证/分页/幂等/错误分类。 */
        A1("认证/分页/幂等/错误分类", Level.E3, Level.E4),
        /** 缺凭证显式失败（不得返回空列表/null/占位号）。 */
        A2("缺凭证显式失败", Level.E3, Level.E3),
        /** 启动自检（非法密钥长度、缺凭证、mock profile 三种情形均拒绝启动）。 */
        A3("启动自检", Level.E3, Level.E4),
        /** 凭证归属与轮换（两店隔离 + 密文落库 + 轮换/吊销）。 */
        A4("凭证归属与轮换", Level.E3, Level.E4),
        /** 以联调记录为准（唯一取证路径，无凭证阶段上限 E1）。 */
        A5("以联调记录为准", Level.E1, Level.E4),
        /** 能力清单一致（实现 operation ⊆ 清单）。 */
        A6("能力清单一致", Level.E3, Level.E3),
        /** 失败可重放（注入失败桩 + 重试/DLQ/重放测试）。 */
        A7("失败可重放", Level.E3, Level.E4),
        /** 限流与配额真实（逐 operation 配额与官方 usage plan 比对）。 */
        A8("限流与配额真实", Level.E3, Level.E4);

        private final String label;
        private final Level offlineCeiling;
        private final Level requiredLevel;

        Criterion(String label, Level offlineCeiling, Level requiredLevel) {
            this.label = label;
            this.offlineCeiling = offlineCeiling;
            this.requiredLevel = requiredLevel;
        }

        public String label() {
            return label;
        }

        /** 无凭证阶段可达的最高等级；超过该等级的声明必须由真实联调提供。 */
        public Level offlineCeiling() {
            return offlineCeiling;
        }

        /** 标记 API-Ready 时本项必须达到的最低等级。 */
        public Level requiredLevel() {
            return requiredLevel;
        }

        /** 无凭证阶段是否可达（A5 之外全部可达 E3）。 */
        public boolean offlineReachable() {
            return offlineCeiling.atLeast(Level.E3);
        }
    }

    /**
     * 一条证据声明。
     *
     * @param criterion 标准（A1–A8）
     * @param level     等级；null 视作 {@link Level#E0}
     * @param source    取证来源（测试类名 / 文档 URL / 联调记录路径），用于审计，不得含明文密钥
     */
    public record Evidence(Criterion criterion, Level level, String source) {
    }

    /**
     * 判定结果。
     *
     * @param evidenceLevel 整体等级 = A1–A8 的最弱一环
     * @param apiReady      是否达到 API-Ready（每条标准均 ≥ {@link Criterion#requiredLevel()}）
     * @param reachable     是否已与平台真实连通（A5 ≥ E4）
     * @param levels        逐条生效等级（缺失项为 E0）
     * @param blockers      未达 {@code requiredLevel} 的标准（按枚举顺序）
     */
    public record Assessment(Level evidenceLevel,
                            boolean apiReady,
                            boolean reachable,
                            Map<Criterion, Level> levels,
                            List<Criterion> blockers) {

        /** 对外可用的三种表述之一（见类注释的硬约束）。 */
        public String displayText() {
            if (apiReady) {
                return "API-Ready（已联调）";
            }
            if (reachable) {
                return "已接通（联调中）";
            }
            return "具备对接能力（未联调）";
        }

        /** 未达标标准的可读列表，如 {@code "A1(E2< E4), A5(E0< E4)"}。 */
        public String blockerSummary() {
            if (blockers.isEmpty()) {
                return "(无)";
            }
            StringBuilder sb = new StringBuilder();
            for (Criterion criterion : blockers) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(criterion.name()).append('(')
                        .append(levelOf(criterion)).append('<').append(criterion.requiredLevel())
                        .append(')');
            }
            return sb.toString();
        }

        public Level levelOf(Criterion criterion) {
            Level level = levels.get(criterion);
            return level == null ? Level.E0 : level;
        }
    }

    /**
     * 依据证据声明做判定。
     * <p>
     * 同一标准的多次声明取<b>最高</b>等级（证据是「已取得的最强证据」，不是账本余额）；
     * 未声明的标准一律按 E0 处理——「没说」不等于「达标」。
     *
     * @param evidence 证据声明集合；null / 空集合 → 全 E0
     */
    public static Assessment evaluate(Collection<Evidence> evidence) {
        Map<Criterion, Level> levels = new EnumMap<>(Criterion.class);
        if (evidence != null) {
            for (Evidence item : evidence) {
                if (item == null || item.criterion() == null) {
                    continue;
                }
                Level candidate = item.level() == null ? Level.E0 : item.level();
                Level current = levels.get(item.criterion());
                if (current == null || candidate.rank() > current.rank()) {
                    levels.put(item.criterion(), candidate);
                }
            }
        }

        Level overall = Level.E5;
        List<Criterion> blockers = new ArrayList<>();
        for (Criterion criterion : Criterion.values()) {
            Level level = levels.getOrDefault(criterion, Level.E0);
            overall = Level.weaker(overall, level);
            if (!level.atLeast(criterion.requiredLevel())) {
                blockers.add(criterion);
            }
        }

        boolean reachable = levels.getOrDefault(Criterion.A5, Level.E0).atLeast(Level.E4);
        return new Assessment(overall, blockers.isEmpty(), reachable,
                Map.copyOf(levels), List.copyOf(blockers));
    }

    /** 无凭证阶段（离线）可达的整体等级 = min(各标准 offlineCeiling)，用于对外声明上限。 */
    public static Level offlineCeiling() {
        Level ceiling = Level.E5;
        for (Criterion criterion : Criterion.values()) {
            ceiling = Level.weaker(ceiling, criterion.offlineCeiling());
        }
        return ceiling;
    }
}
