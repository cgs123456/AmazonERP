package com.amz.dto;

import com.amz.model.FeeDiscrepancy;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 费用差异扫描报告。
 * <p>
 * 与结算导入报告同样的原则：把「扫了多少」「识别出多少」「因为已存在而跳过多少」
 * 「因为算不出来而放弃多少」分开列示 —— 合并成一个数字就无法判断是「没有差异」
 * 还是「费用预估拿不到所以压根没算」。
 */
@Data
public class FeeDiscrepancyScanReport {

    private Long shopId;

    /** 参与扫描的 SKU 数。 */
    private int scannedSkus;
    /** 参与扫描的结算明细行数。 */
    private int scannedSettlementRows;
    /** 新生成的候选数。 */
    private int created;
    /** 因已有未结案同类型候选而跳过的数量。 */
    private int skippedExisting;
    /** 因缺少费用预估而未能评估的 SKU 数。 */
    private int skippedNoEstimate;
    /** 无 SKU 归属而跳过的结算行数。 */
    private int unattributedRows;

    /** 按类型统计新生成候选数。 */
    private Map<String, Integer> byType = new LinkedHashMap<>();
    /** 候选涉及金额合计（按差额绝对值求和，正负方向不同但都是可追回金额）。 */
    private BigDecimal claimableAmount = BigDecimal.ZERO;

    /** 生效的准入阈值（差额低于此值不生成候选，避免噪声淹没真问题）。 */
    private BigDecimal toleranceAmount;

    private List<FeeDiscrepancy> candidates = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public void countType(String type) {
        byType.merge(type, 1, Integer::sum);
    }
}
