package com.amz.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 分块批量写入 + 失败退回逐条的通用流程。
 * <p>
 * 存在的理由（2026-10-01 审查轮 Low，用户选定语义）：热写入路径此前逐条 insert，
 * 一个几千行的结算文件就是几千次往返。但直接换成批量会把两件事弄丢：
 * <b>哪几行失败</b>的归属，以及<b>自增主键回填</b>。所以语义定为：
 * <ol>
 *   <li>按块尝试批量写；</li>
 *   <li>某块抛异常 → <b>只把这一块</b>退回逐条，逐条结果按行归类；</li>
 *   <li>退回时唯一键冲突算「已存在/跳过」，不算失败 —— 批量执行器可能在异常前
 *       已经 flush 了块内的若干行，重试撞上的重复行确实已经在库里。</li>
 * </ol>
 * 本类刻意不直接依赖 MyBatis-Plus：批操作与逐行操作都由调用方以 lambda 注入，
 * 这样分块与归类的逻辑能被普通单测覆盖，而 {@code Db.saveBatch} 那层留在
 * 集成环境里验。
 */
public final class BatchInserts {

    /** 与既有写入路径的 {@code amz.finance.settlement.batch-size} 默认值保持一致。 */
    public static final int DEFAULT_CHUNK_SIZE = 200;

    private BatchInserts() {
    }

    public enum RowOutcome {
        INSERTED, SKIPPED, FAILED
    }

    /** 一次分块写入的累计结果。 */
    public static final class Result {
        private int inserted;
        private int skipped;
        private int failed;
        private int fallbackChunks;
        private final List<String> failures = new ArrayList<>();

        public int getInserted() {
            return inserted;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getFailed() {
            return failed;
        }

        /** 有多少块从批量退回到了逐条。非 0 就说明批量这条路在当前数据上走不通。 */
        public int getFallbackChunks() {
            return fallbackChunks;
        }

        public List<String> getFailures() {
            return failures;
        }
    }

    /**
     * @param rows       待写入行
     * @param chunkSize  每块行数
     * @param batchOp    批量写入，失败即抛异常（整块视作需要退回逐条）
     * @param rowOp      逐条写入；必须自己把「唯一键冲突」翻译成 {@link RowOutcome#SKIPPED}
     * @param labelOf    行标识（进失败清单，便于定位）
     * @param maxFailures 失败清单上限，超出只计数不再收集
     */
    public static <T> Result saveChunks(List<T> rows, int chunkSize,
                                        Consumer<List<T>> batchOp,
                                        Function<T, RowOutcome> rowOp,
                                        Function<T, String> labelOf,
                                        int maxFailures) {
        Result result = new Result();
        if (rows == null || rows.isEmpty()) {
            return result;
        }
        int chunk = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        for (int from = 0; from < rows.size(); from += chunk) {
            List<T> block = rows.subList(from, Math.min(from + chunk, rows.size()));
            if (block.size() == 1) {
                // 单行块没有"批量"可言，直接走逐条，省一次必然失败的批量尝试
                applyRow(block.get(0), rowOp, labelOf, result, maxFailures);
                continue;
            }
            try {
                batchOp.accept(block);
                result.inserted += block.size();
            } catch (Exception batchError) {
                result.fallbackChunks++;
                for (T row : block) {
                    applyRow(row, rowOp, labelOf, result, maxFailures);
                }
            }
        }
        return result;
    }

    private static <T> void applyRow(T row, Function<T, RowOutcome> rowOp, Function<T, String> labelOf,
                                     Result result, int maxFailures) {
        RowOutcome outcome;
        try {
            outcome = rowOp.apply(row);
        } catch (Exception unexpected) {
            outcome = RowOutcome.FAILED;
            if (result.failures.size() < maxFailures) {
                result.failures.add(labelOf.apply(row) + "：" + unexpected.getMessage());
            }
            result.failed++;
            return;
        }
        switch (outcome) {
            case INSERTED -> result.inserted++;
            case SKIPPED -> result.skipped++;
            case FAILED -> {
                result.failed++;
                if (result.failures.size() < maxFailures) {
                    result.failures.add(labelOf.apply(row) + "：逐条重试仍失败");
                }
            }
        }
    }
}
