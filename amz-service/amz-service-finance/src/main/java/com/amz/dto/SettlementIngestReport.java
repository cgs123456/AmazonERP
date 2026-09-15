package com.amz.dto;

import com.amz.parse.SettlementParser;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结算原表导入报告。
 * <p>
 * 刻意区分三个数字：<b>读进来的行数</b>（dataLineCount）、<b>真正入库的行数</b>（inserted）、
 * <b>被跳过的重复行数</b>（skipped）。三者混成一个「成功数」会让
 * 「重复拉取被正确去重」和「数据根本没拉到」看起来一模一样。
 */
@Data
public class SettlementIngestReport {

    private Long shopId;
    private String reportType;
    /** SP-API 报表 ID。 */
    private String reportId;
    /** 报表最终状态（DONE / FATAL / ...）。 */
    private String reportStatus;

    /** 参与解析的数据行数（不含表头与空行）。 */
    private int dataLineCount;
    /** 实际入库行数。 */
    private int inserted;
    /** 因业务指纹重复而跳过的行数（幂等命中的正常情况）。 */
    private int skipped;
    /** 解析或落库失败的行数。 */
    private int failed;

    /** 本批入库金额合计（有符号）。 */
    private BigDecimal sumAmount = BigDecimal.ZERO;
    /** 本批涉及的币种（多币种不求和，只分别列示）。 */
    private Set<String> currencies = new LinkedHashSet<>();

    /** 行级错误（已截断，见 warnings 中的说明）。 */
    private List<SettlementParser.RowError> rowErrors = new ArrayList<>();
    /** 口径提示：多币种、空结果、行级错误截断、状态异常等。 */
    private List<String> warnings = new ArrayList<>();

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
}
