package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多平台订单的分平台统计（A8）。
 *
 * <p><b>口径：分平台</b>——每个平台各给一份统计，不产出一个跨平台的「全店销售额」。
 * 理由：Temu / TikTok / Shein 与 Amazon 的币种、物流、平台费结构都不同，
 * 把 original_amount 直接相加得到的数字无法对应任何真实经营口径，
 * 那样的合计会让使用者以为那是可对外报的营收。
 *
 * <p>因此这里刻意<b>不给合计行</b>：使用者需要总量时自行按平台看，
 * 页面也不渲染「总计」——要加总和必须先定汇率与费用归集口径，那是独立决策。
 *
 * <p>金额按原始币种分组统计（{@code byCurrency}）：同一平台可能多币种，
 * 混在一起加总同样是假数字。
 */
@Data
public class PlatformOrderStats {

    /** 按平台分组的统计（平台 → 该平台的统计）。 */
    private List<PlatformBucket> platforms = new ArrayList<>();

    /** 参与统计的订单总行数（各平台之和），用于「覆盖了多少单」的完整性判断。 */
    private int totalOrders;

    /** 统计时被截断的平台清单：非空表示还有数据没算进来。 */
    private List<String> truncatedPlatforms = new ArrayList<>();

    @Data
    public static class PlatformBucket {
        private String platform;

        /** 该平台的订单行数。 */
        private int orderCount;

        /** 按币种分组的金额合计（币种 → 合计）。 */
        private Map<String, BigDecimal> amountByCurrency = new LinkedHashMap<>();

        /** 按币种分组的人民币折算合计（币种 → 合计；未折算则为 null）。 */
        private Map<String, BigDecimal> cnyByCurrency = new LinkedHashMap<>();

        /** 按状态分组的行数（状态 → 行数）。 */
        private Map<String, Integer> statusCounts = new LinkedHashMap<>();

        /** 该平台是否被行数上限截断。 */
        private boolean truncated;
    }
}
