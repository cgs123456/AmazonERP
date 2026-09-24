package com.amz.connector;

import com.amz.connector.ConnectorEvidencePolicy.Assessment;
import com.amz.connector.ConnectorEvidencePolicy.Criterion;
import com.amz.connector.ConnectorEvidencePolicy.Evidence;
import com.amz.connector.ConnectorEvidencePolicy.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-51 / spec §1.9.1：连接器证据门禁（E0–E5 × A1–A8）。
 * <p>
 * 本测试把用户口径「我暂时没有对接 API，但需要有对接能力」变成<b>可判定</b>的结论：
 * 无凭证阶段整体等级上限为 E1，{@link Assessment#displayText()} 只能输出
 * 「具备对接能力（未联调）」——不得输出「已接通」或「有 API 即可直接使用」。
 * <p>
 * 证据类型：E1（自证，本测试自身）。本类不产生 E4/E5，也不得被引用为 A5 通过的依据。
 */
@DisplayName("P0-51 连接器证据门禁（E0–E5 / A1–A8）")
class ConnectorEvidencePolicyTest {

    private static Evidence at(Criterion criterion, Level level) {
        return new Evidence(criterion, level, "ConnectorEvidencePolicyTest");
    }

    private static List<Evidence> allAt(Level level) {
        List<Evidence> evidence = new ArrayList<>();
        for (Criterion criterion : Criterion.values()) {
            evidence.add(at(criterion, level));
        }
        return evidence;
    }

    @Test
    @DisplayName("E0–E5 单调：rank 递增，atLeast/weaker 是唯一比较入口")
    void levelsAreMonotonic() {
        Level[] levels = Level.values();
        assertEquals(6, levels.length, "E0–E5 恰好六级");
        for (int i = 0; i < levels.length; i++) {
            assertEquals(i, levels[i].rank(), levels[i].name() + " rank 必须等于序号");
            assertTrue(levels[i].atLeast(Level.E0), "任何等级都不低于 E0");
            if (i > 0) {
                assertFalse(levels[0].atLeast(levels[i]), "E0 不得被认为达到 " + levels[i]);
            }
        }
        assertTrue(Level.E4.atLeast(Level.E4), "atLeast 含自身");
        assertTrue(Level.E4.atLeast(null), "null 视作 E0");
        assertEquals(Level.E1, Level.weaker(Level.E3, Level.E1));
        assertEquals(Level.E0, Level.weaker(null, Level.E0));
        assertEquals(Level.E0, Level.weaker(Level.E5, null), "null 视作 E0：任何等级与 null 取弱都落到 E0");
    }

    @Test
    @DisplayName("Level.parse 宽松解析合法值，非法值显式失败（不得静默回落 E0）")
    void parseIsStrict() {
        assertEquals(Level.E3, Level.parse("e3"));
        assertEquals(Level.E3, Level.parse(" E3 "));
        assertThrows(IllegalArgumentException.class, () -> Level.parse("E9"));
        assertThrows(IllegalArgumentException.class, () -> Level.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Level.parse(null));
    }

    @Test
    @DisplayName("A1–A8 的 offlineCeiling / requiredLevel / offlineReachable 与 spec 一致")
    void criterionMetadataMatchesSpec() {
        assertEquals(8, Criterion.values().length, "A1–A8 恰好八条");
        for (Criterion criterion : Criterion.values()) {
            assertTrue(criterion.requiredLevel().atLeast(criterion.offlineCeiling()),
                    criterion.name() + " 的取证要求不得低于无凭证上限");
        }
        // A2/A6 是「实现内部可自证」的条款：只要求 E3
        assertEquals(Level.E3, Criterion.A2.requiredLevel());
        assertEquals(Level.E3, Criterion.A6.requiredLevel());
        // 其余条款（含 A1/A3/A4/A5/A7/A8）标记 API-Ready 时必须 E4（真实联调）
        assertEquals(Level.E4, Criterion.A1.requiredLevel());
        assertEquals(Level.E4, Criterion.A3.requiredLevel());
        assertEquals(Level.E4, Criterion.A4.requiredLevel());
        assertEquals(Level.E4, Criterion.A5.requiredLevel());
        assertEquals(Level.E4, Criterion.A7.requiredLevel());
        assertEquals(Level.E4, Criterion.A8.requiredLevel());
        // A5（以联调记录为准）无凭证阶段上限 E1：没有真实凭证就不可能取证
        assertEquals(Level.E1, Criterion.A5.offlineCeiling());
        assertFalse(Criterion.A5.offlineReachable());
        assertTrue(Criterion.A1.offlineReachable());
        // 整体无凭证上限 = min(各条 offlineCeiling) = E1
        assertEquals(Level.E1, ConnectorEvidencePolicy.offlineCeiling());
    }

    @Test
    @DisplayName("空证据：全条 E0，绝不 API-Ready，口径为「具备对接能力（未联调）」")
    void emptyEvidenceIsNeverApiReady() {
        Assessment assessment = ConnectorEvidencePolicy.evaluate(List.of());
        assertEquals(Level.E0, assessment.evidenceLevel());
        assertFalse(assessment.apiReady());
        assertFalse(assessment.reachable());
        assertEquals("具备对接能力（未联调）", assessment.displayText());
        assertEquals(8, assessment.blockers().size(), "未声明的标准全部计入缺口");
        assertEquals(Criterion.A1, assessment.blockers().get(0), "缺口按枚举顺序输出");
        assertEquals(Level.E0, ConnectorEvidencePolicy.evaluate(null).evidenceLevel(), "null 等价空集合");
    }

    @Test
    @DisplayName("无凭证阶段：每条都取 offlineCeiling 仍不是 API-Ready（上限 E1）")
    void offlineCeilingIsNotApiReady() {
        List<Evidence> evidence = new ArrayList<>();
        for (Criterion criterion : Criterion.values()) {
            evidence.add(at(criterion, criterion.offlineCeiling()));
        }
        Assessment assessment = ConnectorEvidencePolicy.evaluate(evidence);

        assertEquals(Level.E1, assessment.evidenceLevel(), "最弱一环仍是 A5 的 E1");
        assertFalse(assessment.apiReady());
        assertFalse(assessment.reachable());
        assertEquals("具备对接能力（未联调）", assessment.displayText());
        assertTrue(assessment.blockers().contains(Criterion.A5));
        assertEquals(Level.E1, assessment.levelOf(Criterion.A5));
    }

    @Test
    @DisplayName("已真实联调但标准未补齐 → 「已接通（联调中）」（reachable 与 apiReady 分离）")
    void reachableButNotReady() {
        Assessment assessment = ConnectorEvidencePolicy.evaluate(List.of(at(Criterion.A5, Level.E4)));

        assertTrue(assessment.reachable(), "A5 ≥ E4 必须有真实联调记录");
        assertFalse(assessment.apiReady(), "其余标准仍是 E0");
        assertEquals("已接通（联调中）", assessment.displayText());
        assertEquals(Level.E4, assessment.levelOf(Criterion.A5));
        assertEquals(Level.E0, assessment.evidenceLevel(), "最弱一环是未声明的标准");
        assertEquals(7, assessment.blockers().size());
        assertFalse(assessment.blockers().contains(Criterion.A5));
    }

    @Test
    @DisplayName("全部达到 requiredLevel → API-Ready；整体等级取最弱一环（E3）")
    void allCriteriaAtRequiredLevelIsApiReady() {
        List<Evidence> evidence = new ArrayList<>();
        for (Criterion criterion : Criterion.values()) {
            evidence.add(at(criterion, criterion.requiredLevel()));
        }
        Assessment assessment = ConnectorEvidencePolicy.evaluate(evidence);

        assertTrue(assessment.apiReady());
        assertTrue(assessment.reachable());
        assertEquals("API-Ready（已联调）", assessment.displayText());
        assertEquals(Level.E3, assessment.evidenceLevel(), "A2/A6 只到 E3，故最弱一环是 E3");
        assertTrue(assessment.blockers().isEmpty());
        assertEquals("(无)", assessment.blockerSummary());
    }

    @Test
    @DisplayName("同一标准多次声明取最高等级（证据是已取得的最强证据）")
    void duplicateEvidenceTakesStrongest() {
        Assessment assessment = ConnectorEvidencePolicy.evaluate(List.of(
                at(Criterion.A1, Level.E1),
                at(Criterion.A1, Level.E3),
                at(Criterion.A1, Level.E2)));

        assertEquals(Level.E3, assessment.levelOf(Criterion.A1));
        assertEquals(Level.E0, assessment.evidenceLevel(),
                "整体等级取 A1–A8 最弱一环：只声明 A1 时其余标准仍是 E0");
        assertFalse(assessment.apiReady(), "A1 到 E3 但未到 requiredLevel E4");
    }

    @Test
    @DisplayName("blockerSummary 逐条给出「实测<要求」，可直接贴进验收记录")
    void blockerSummaryListsGaps() {
        Assessment assessment = ConnectorEvidencePolicy.evaluate(List.of(at(Criterion.A1, Level.E2)));

        String summary = assessment.blockerSummary();
        assertTrue(summary.contains("A1(E2<E4)"), summary);
        assertTrue(summary.contains("A2(E0<E3)"), summary);
        assertFalse(summary.contains("A1(E2<E3)"), "A1 的要求是 E4：" + summary);
    }

    @Test
    @DisplayName("null 条目 / null 等级不得伪造证据")
    void nullEntriesAreIgnored() {
        Assessment assessment = ConnectorEvidencePolicy.evaluate(List.of(
                new Evidence(Criterion.A1, null, "test"),
                new Evidence(null, Level.E5, "test")));

        assertEquals(Level.E0, assessment.levelOf(Criterion.A1));
        assertEquals(Level.E0, assessment.evidenceLevel());
    }
}
