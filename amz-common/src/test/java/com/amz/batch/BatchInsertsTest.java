package com.amz.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块 + 退回逐条这条流程本身的行为。
 * <p>
 * 关键点不是"快"，而是<b>换批量之后仍然说得出哪几行没进去</b>：
 * 只有出问题的块退回逐条（其余块保持批量），且批量执行器可能在抛异常前已经写入
 * 块内前几行 —— 这时逐条重试撞上唯一键冲突必须算「跳过」，不能算失败，
 * 否则报表会说"这行没入库"，而它其实已经在库里了。
 */
@DisplayName("批量写入分块与退回语义")
class BatchInsertsTest {

    private static List<String> rows(int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add("r" + i);
        }
        return out;
    }

    @Test
    @DisplayName("全部成功时只按块调用批量，一次都不退回逐条")
    void happyPathUsesBatchOnly() {
        List<List<String>> blocks = new ArrayList<>();
        AtomicInteger singles = new AtomicInteger();
        BatchInserts.Result result = BatchInserts.saveChunks(
                rows(5), 2,
                block -> blocks.add(new ArrayList<>(block)),
                row -> {
                    singles.incrementAndGet();
                    return BatchInserts.RowOutcome.INSERTED;
                },
                row -> row, 10);

        assertEquals(2, blocks.size(), "5 行 / 块 2 → 批量只吃满块（2+2），尾行直接逐条");
        assertEquals(List.of(2, 2), blocks.stream().map(List::size).toList());
        assertEquals(5, result.getInserted());
        assertEquals(0, result.getFallbackChunks());
        // 尾块只有 1 行：批量没有意义，直接逐条
        assertEquals(1, singles.get());
    }

    @Test
    @DisplayName("某块失败只让该块退回逐条，其余块保持批量")
    void onlyTheFailingBlockFallsBack() {
        Set<Integer> batchedSizes = new HashSet<>();
        List<String> perRow = new ArrayList<>();
        Consumer<List<String>> batchOp = block -> {
            batchedSizes.add(block.size());
            if (block.contains("r1")) {
                throw new IllegalStateException("duplicate entry r1");
            }
        };

        BatchInserts.Result result = BatchInserts.saveChunks(
                rows(6), 3, batchOp,
                row -> {
                    perRow.add(row);
                    // 批量执行器可能已把块内前几行 flush 进去了：r1 冲突算跳过
                    return "r1".equals(row)
                            ? BatchInserts.RowOutcome.SKIPPED
                            : BatchInserts.RowOutcome.INSERTED;
                },
                row -> row, 10);

        assertEquals(1, result.getFallbackChunks());
        assertEquals(List.of("r0", "r1", "r2"), perRow, "只有含失败行的那块退回逐条");
        assertEquals(5, result.getInserted());
        assertEquals(1, result.getSkipped(), "唯一键冲突是「已在库里」，不是失败");
        assertEquals(0, result.getFailed());
    }

    @Test
    @DisplayName("逐条仍失败才计入失败，并按上限收集明细")
    void realFailuresAreCountedAndCapped() {
        BatchInserts.Result result = BatchInserts.saveChunks(
                rows(4), 4,
                block -> {
                    throw new IllegalStateException("batch exploded");
                },
                row -> "r0".equals(row) || "r1".equals(row)
                        ? BatchInserts.RowOutcome.FAILED
                        : BatchInserts.RowOutcome.INSERTED,
                row -> row, 1);

        assertEquals(2, result.getFailed());
        assertEquals(2, result.getInserted());
        assertEquals(1, result.getFailures().size(), "明细有上限，计数没有上限");
        assertTrue(result.getFailures().get(0).startsWith("r"), result.getFailures().toString());
    }

    @Test
    @DisplayName("空输入不触发任何写入")
    void emptyInputIsNoOp() {
        AtomicInteger calls = new AtomicInteger();
        List<String> empty = List.of();
        BatchInserts.Result result = BatchInserts.saveChunks(
                empty, 100,
                block -> calls.incrementAndGet(),
                row -> {
                    calls.incrementAndGet();
                    return BatchInserts.RowOutcome.INSERTED;
                },
                row -> row, 10);
        assertEquals(0, calls.get());
        assertEquals(0, result.getInserted());

        List<String> nullRowsList = null;
        BatchInserts.Result nullRows = BatchInserts.saveChunks(
                nullRowsList, 100, block -> calls.incrementAndGet(),
                row -> BatchInserts.RowOutcome.INSERTED, row -> row, 10);
        assertEquals(0, nullRows.getInserted());
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("rowOp 自己抛异常也计入失败，而不是让整批中断")
    void rowOpThrowingIsCountedAsFailureNotPropagated() {
        Function<String, BatchInserts.RowOutcome> explodingRow = row -> {
            throw new IllegalStateException("db down");
        };
        BatchInserts.Result result = BatchInserts.saveChunks(
                rows(3), 1,
                block -> {
                    throw new AssertionError("单行块不该走批量");
                },
                explodingRow,
                row -> row, 5);

        assertEquals(3, result.getFailed());
        assertEquals(0, result.getInserted());
        assertEquals(3, result.getFailures().size());
    }

    @Test
    @DisplayName("退回逐条不等于事情翻篇：批量那一次的失败原因必须留在结果里")
    void batchFailureCauseSurvivesTheFallback() {
        // 逐条重试会把每一行都写成功，结果数字完全正常；如果批量异常在 catch 里被丢掉，
        // "批量这条路永远走不通"（列名改了、foreach 写错）就再也没有痕迹。
        BatchInserts.Result result = BatchInserts.saveChunks(
                rows(4), 2,
                block -> {
                    throw new IllegalStateException("外层包装",
                            new RuntimeException("Unknown column 'row_ky' in 'field list'"));
                },
                row -> BatchInserts.RowOutcome.INSERTED,
                row -> row, 10);

        assertEquals(4, result.getInserted());
        assertEquals(2, result.getFallbackChunks());
        assertEquals(2, result.getBatchErrors().size(),
                "每个失败的块留一条原因，否则无法定位是哪一段数据触发的：" + result.getBatchErrors());
        assertTrue(result.getBatchErrors().get(0).contains("Unknown column 'row_ky'"),
                "要带最里层原因，外层包装的 message 通常不含 SQL 细节：" + result.getBatchErrors());
        assertTrue(result.getBatchErrors().get(0).contains("2 行"),
                "要带上块行数与块起点，便于回看是哪一段：" + result.getBatchErrors());
    }

    @Test
    @DisplayName("isDuplicateKey 认因果链里的约束异常，也认最外层消息；其它异常不认")
    void duplicateKeyPredicateRecognisesWrappedAndMessageOnlyShapes() {
        assertTrue(BatchInserts.isDuplicateKey(
                        new IllegalStateException("批量执行失败",
                                new java.sql.SQLIntegrityConstraintViolationException("Duplicate entry"))),
                "驱动异常被包两层是 MyBatis/Spring 的常态");
        assertTrue(BatchInserts.isDuplicateKey(
                        new RuntimeException("... Duplicate entry 'rk-1' for key 'uk_row_key' ...")),
                "有些执行器只把细节留在消息里");
        assertFalse(BatchInserts.isDuplicateKey(
                        new java.sql.SQLSyntaxErrorException("Unknown column 'row_ky'")),
                "把语法错误认成「库里已有这行」会把真实失败静默记成跳过");
        assertFalse(BatchInserts.isDuplicateKey(new IllegalStateException("connection reset")));
    }
}
