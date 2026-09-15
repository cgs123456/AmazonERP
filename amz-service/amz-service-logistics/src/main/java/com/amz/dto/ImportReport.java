package com.amz.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入结果报告。
 * <p>
 * <b>为什么不是简单的「成功/失败」：</b>导入是使用者自己准备文件后的一次性动作，
 * 若只回「100 条失败」，使用者无法知道该改哪一行。因此报告拆成三组信息：
 * <ol>
 *   <li><b>统计量</b>（accepted/skipped/updated…）—— 判断本次导入整体是否达到预期；</li>
 *   <li><b>unmatched</b> —— 明确回吐「哪些运单在系统里没有货件」，
 *       这是需要使用者去补主单的可执行清单，绝不能静默吞掉；</li>
 *   <li><b>errors</b> —— 行级错误原因（含行号），直接对应到文件里的具体行。</li>
 * </ol>
 * {@code skipped} 与 {@code failed} 语义刻意分开：跳过是「数据已存在，属正常幂等」，
 * 失败是「数据有问题，需人工修」。混在一起会让重复导入看起来像出了故障。
 */
@Data
public class ImportReport {

    /** 本次请求的总行数（货件行数或运单行数） */
    private int totalRows;

    /** 处理成功的行数 */
    private int succeededRows;

    /** 处理失败的行数（含租户冲突、必填缺失等） */
    private int failedRows;

    /** 新建的货件数（仅货件导入） */
    private int createdShipments;

    /** 补齐/更新的货件数（仅货件导入） */
    private int updatedShipments;

    /** 实际写入的轨迹点数 */
    private int acceptedEvents;

    /** 因指纹重复而跳过的轨迹点数 —— 幂等生效的直接体现 */
    private int skippedEvents;

    /** 因本次写入导致整体状态发生变化的货件数 */
    private int statusChangedShipments;

    /**
     * 未匹配到货件的运单标识（货件编号或运单号）。
     * <p>
     * 调用方应把该列表展示给使用者，提示其先补录货件主单后重新导入本次轨迹；
     * 之所以不自动创建货件，是因为仅凭一个运单号无法推断店铺、承运商、ETA 等主档信息，
     * 自动建出的空壳货件会污染看板的时效统计。
     */
    private List<String> unmatched = new ArrayList<>();

    /** 行级错误明细，格式「第 N 行: 原因」 */
    private List<String> errors = new ArrayList<>();

    /** 错误明细上限：只保留前若干条，避免一次错误文件回包过大拖垮前端 */
    private static final int MAX_ERRORS = 50;

    /** 错误计数是否已因超限而截断 */
    private boolean errorsTruncated;

    /** 追加一条错误明细，超过上限则只累加计数并标记截断 */
    public void addError(int rowNo, String reason) {
        if (errors.size() < MAX_ERRORS) {
            errors.add("第 " + rowNo + " 行: " + reason);
        } else {
            errorsTruncated = true;
        }
    }

    /** 追加一条运单未匹配提示，同样受上限保护 */
    public void addUnmatched(String identifier) {
        if (unmatched.size() < MAX_ERRORS) {
            unmatched.add(identifier);
        } else {
            errorsTruncated = true;
        }
    }

    /** 汇总一行成功，供服务层复用避免遗漏计数 */
    public void countSuccess() {
        succeededRows++;
    }

    /** 汇总一行失败 */
    public void countFailure() {
        failedRows++;
    }
}
