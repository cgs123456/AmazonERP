package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 索赔与平台赔付的双向核对报告（T08）。
 * <p>
 * 核对两侧：
 * <ul>
 *   <li><b>平台侧</b>：结算原表中的 Adjustment 行（FBA 库存赔付等，钱真的到账了）</li>
 *   <li><b>系统侧</b>：本系统已标记「已赔付」的索赔单（我们账上认为收到的钱）</li>
 * </ul>
 * <b>两个方向的差异含义完全不同，必须分开列示</b>：
 * <ul>
 *   <li>{@code platformOnly} 平台赔了但系统没有对应索赔单 —— 多为平台主动赔付，
 *       不需要索赔动作，但要确认金额已计入收入</li>
 *   <li>{@code systemOnly} 系统记了赔付但平台侧没有对应调整 —— <b>这是账实不符</b>，
 *       可能是结算数据未同步（漏拉报表）或人工误标，需要优先排查</li>
 * </ul>
 * 合并成一个「差异笔数」就把这两种完全不同的处置路径抹掉了。
 */
@Data
public class ReimbursementReconcileReport {

    private Long shopId;

    /** 双侧匹配上的笔数。 */
    private int matched;
    /** 平台侧调整笔数（含匹配与未匹配）。 */
    private int platformAdjustmentCount;
    /** 平台侧赔付金额合计。 */
    private BigDecimal platformTotal = BigDecimal.ZERO;
    /** 系统侧已赔付金额合计。 */
    private BigDecimal systemTotal = BigDecimal.ZERO;
    /** 两侧金额差（系统 - 平台）。 */
    private BigDecimal difference = BigDecimal.ZERO;

    /** 平台赔了但系统无索赔单。 */
    private List<Item> platformOnly = new ArrayList<>();
    /** 系统记了赔付但平台无对应调整（账实不符，优先排查）。 */
    private List<Item> systemOnly = new ArrayList<>();

    private List<String> warnings = new ArrayList<>();

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    /**
     * 核对明细项。
     */
    @Data
    public static class Item {
        /** 平台侧：AdjustmentType；系统侧：索赔单号。 */
        private String reference;
        private String sku;
        private BigDecimal amount;
        private String currency;
        /** 平台侧：入账时间；系统侧：结案时间。 */
        private String postedAt;

        public Item(String reference, String sku, BigDecimal amount, String currency, String postedAt) {
            this.reference = reference;
            this.sku = sku;
            this.amount = amount;
            this.currency = currency;
            this.postedAt = postedAt;
        }
    }
}
