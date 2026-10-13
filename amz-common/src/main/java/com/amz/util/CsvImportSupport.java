package com.amz.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 导入的行循环骨架（A6 抽象）。
 *
 * <p>三个真实导入域（商品 upsert、库存元数据 update-only、供货价 update-only）
 * 把同一段循环写了两遍后，第三个域是抽它的时机——不是提前抽象，而是重复已发生。
 *
 * <p>只抽<b>真正因同一原因重复</b>的部分：解析后逐行处理、文件行号（表头 1 行 +
 * 前面已处理数据行数 + 1，1 基）、坏行折叠进行级错误、按结果累计计数。
 * <b>刻意不抽</b>报告 DTO 与行处理语义——商品是 upsert（有 inserted 计数），
 * 库存/供货价是 update-only（<b>没有</b> inserted，且各自拒绝不同列：库存拒数量列、
 * 供货价拒身份/首选列）。把报告强行并成一个会抹掉「库存报告刻意不显示新增」这类
 * 诚实信号，也套不进各域不同的定位/拒绝规则（原则：不因外形相似而过早抽象）。
 *
 * <p>行处理通过 {@link RowHandler} 注入：返回 {@link RowOutcome} 归类计数，
 * 抛异常即该行失败（原因带行号收集）。
 */
public final class CsvImportSupport {

    private CsvImportSupport() {
    }

    /** 单行处理结果。failures 走异常，不占 outcome。 */
    public enum RowOutcome {
        INSERTED, UPDATED, SKIPPED
    }

    @FunctionalInterface
    public interface RowHandler {
        /**
         * 处理一行。返回归类；抛异常 = 该行失败（消息作为原因）。
         *
         * @param lineNumber 文件行号（1 基，含表头行），供需要时写入更精确的原因
         */
        RowOutcome handle(int lineNumber, Map<String, String> row) throws Exception;
    }

    /** 累计结果。调用方按各自报告 DTO 取用需要的字段（update-only 域不读 insertedRows）。 */
    public static final class Accumulator {
        public int totalRows;
        public int insertedRows;
        public int updatedRows;
        public int skippedRows;
        public List<String> headers = new ArrayList<>();
        public Map<Integer, String> errors = new LinkedHashMap<>();

        public int failedRows() {
            return errors.size();
        }
    }

    /**
     * 跑一遍行循环。
     *
     * @param parsed  已解析的 CSV（结构错误应在调用方 parse 阶段就抛出，不进这里）
     * @param handler 行处理器
     */
    public static Accumulator run(CsvImportUtil.Parsed parsed, RowHandler handler) {
        Accumulator acc = new Accumulator();
        acc.totalRows = parsed.totalRows();
        acc.headers = parsed.headers();

        for (int i = 0; i < parsed.rows().size(); i++) {
            int lineNumber = i + 2;
            try {
                switch (handler.handle(lineNumber, parsed.rows().get(i))) {
                    case INSERTED:
                        acc.insertedRows++;
                        break;
                    case UPDATED:
                        acc.updatedRows++;
                        break;
                    case SKIPPED:
                        acc.skippedRows++;
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                // 行号对着文件说，使用者能直接跳过去改
                acc.errors.put(lineNumber, e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        // 解析阶段的坏行（列数不符等）与业务失败分开产生，但都带行号、并进同一张错误表
        for (CsvImportUtil.BadRow bad : parsed.badRows()) {
            acc.errors.put(bad.lineNumber(), bad.reason());
        }
        return acc;
    }
}
