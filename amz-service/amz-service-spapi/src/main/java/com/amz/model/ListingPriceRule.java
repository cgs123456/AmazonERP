package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Listing 调价规则（P3.2）。
 * <p>
 * 目标价与调整幅度二选一：target_price 直接定价；adjust_pct 相对当前价调整
 * （0.05 = -5%？——不，正数上调、负数下调，-0.05 = 降价 5%）。
 * 执行经统一 operation 执行器（listingsItems.patchListingsItem，官方快照）。
 */
@Data
@TableName("amz_listing_price_rule")
public class ListingPriceRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String marketplaceId;

    private String sku;

    /** MANUAL_SCHEDULE（预留 TRACK_COMPETITOR 等） */
    private String ruleType;

    private BigDecimal targetPrice;

    /** 正数上调 / 负数下调（小数形式，-0.05 = 降价 5%） */
    private BigDecimal adjustPct;

    private Integer enabled;

    private LocalDateTime lastExecutedAt;

    private String lastResult;

    private String remark;

    private Long operatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
