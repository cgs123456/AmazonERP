package com.amz.batch;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 分块批量写入 + 失败退回逐条的通用流程。
 * <p>
 * 存在的理由：热写入路径逐条 insert 时，一个几千行的结算文件就是几千次往返。
 * 但直接换成批量会把两件事弄丢：<b>哪几行失败</b>的归属，以及<b>自增主键回填</b>。
 * 所以语义定为：
 * <ol>
 *   <li>按块尝试批量写；</li>
 *   <li>某块抛异常 → <b>只把这一块</b>退回逐条，逐条结果按行归类；</li>
 *   <li>退回时唯一键冲突算「已存在/跳过」，不算失败。</li>
 * </ol>
 * 第 3 条不是保守，而是实测出来的（MySQL 8.4 / InnoDB，隔离实例）：
 * <ul>
 *   <li>单条 {@code INSERT ... VALUES (...),(...)} 含重复键时整条语句回滚，块内 0 行入库；</li>
 *   <li>逐条语句在 autocommit 下，失败前的那几条<b>已经提交</b>（实测 a,b,e 在、冲突的 f 不在）；</li>
 *   <li>同一形状在开着的事务里则随事务一起回滚（实测 ROLLBACK 后 0 行）。</li>
 * </ul>
 * MyBatis 的 BATCH 执行器属于第二种形状，而结算这类 ingest 方法没有 {@code @Transactional}
 * —— 所以块失败后逐条重试确实会撞上"上一半已写进去"的行，把它们记成失败就会谎报"没入库"。
 * <p>
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
        private final List<String> batchErrors = new ArrayList<>();

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

        /**
         * 批量那一次尝试的失败原因（根因类名 + 消息），逐块一条，上限同失败清单。
         * 退回到逐条只是让结果正确，原因本身必须留下：不然"批量永远走不通"这种
         * 持续性故障（列名改了、{@code <foreach>} 写错）会带着逐条的成功一起消失。
         */
        public List<String> getBatchErrors() {
            return batchErrors;
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
                if (result.batchErrors.size() < maxFailures) {
                    result.batchErrors.add("块起点 " + from + "（" + block.size() + " 行）："
                            + rootCauseOf(batchError));
                }
                for (T row : block) {
                    applyRow(row, rowOp, labelOf, result, maxFailures);
                }
            }
        }
        return result;
    }

    /**
     * 唯一键冲突的识别要看整条因果链：驱动给出的类型会被 MyBatis / Spring 逐层包装，
     * 只看最外层就会把"库里已经有这一行"记成失败，报表随之少一笔。
     * <p>
     * 放在这里而不是各调用方自己写一遍：{@code rowOp} 的契约要求调用方把唯一键冲突
     * 翻译成 {@link RowOutcome#SKIPPED}，判定规则只该有一份实现，并且集成测试要能
     * 直接拿真实异常打到这段代码上（复制一份去测，测的就不是生产用的那个判断）。
     */
    public static boolean isDuplicateKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 最深层原因（MyBatis 会把 SQL 错误包两三层，最里面那条才带列名）。 */
    private static String rootCauseOf(Throwable error) {
        Throwable deepest = error;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        return deepest.getClass().getSimpleName() + ": " + deepest.getMessage();
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
