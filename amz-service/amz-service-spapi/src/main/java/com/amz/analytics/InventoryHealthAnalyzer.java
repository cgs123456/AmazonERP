package com.amz.analytics;

import com.amz.model.FbaInventory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * FBA 库存健康度分析器。
 * <p>
 * 基于库存可供天数（Days Of Supply, DOS）将 SKU 划分为 5 个健康度等级：
 * <ul>
 *   <li>STOCKOUT  —— available = 0，断货</li>
 *   <li>URGENT    —— DOS ≤ 7，紧急补货</li>
 *   <li>AT_RISK   —— 7 &lt; DOS ≤ 14，预警</li>
 *   <li>HEALTHY   —— 14 &lt; DOS ≤ 60，健康</li>
 *   <li>OVERSTOCK —— DOS &gt; 60，库存积压</li>
 * </ul>
 * DOS = available / max(avg_7_days / 7, avg_30_days / 30)。
 */
@Component
public class InventoryHealthAnalyzer {

    /**
     * DOS 阈值：URGENT 上限。
     */
    private static final BigDecimal DOS_URGENT = BigDecimal.valueOf(7);

    /**
     * DOS 阈值：AT_RISK 上限。
     */
    private static final BigDecimal DOS_AT_RISK = BigDecimal.valueOf(14);

    /**
     * DOS 阈值：HEALTHY 上限。
     */
    private static final BigDecimal DOS_HEALTHY = BigDecimal.valueOf(60);

    /**
     * 当无销量数据（avg 全 0）时填入的 DOS 占位值，视为长期可供。
     */
    private static final BigDecimal DOS_NO_SALES = BigDecimal.valueOf(999);

    /**
     * 计算库存健康度并回填到实体。
     *
     * @param inv FBA 库存实体（avg7Days / avg30Days 已填充）
     * @return 入参本身，便于链式调用
     */
    public FbaInventory analyze(FbaInventory inv) {
        if (inv == null) {
            return null;
        }
        int available = inv.getAvailableQuantity() == null ? 0 : inv.getAvailableQuantity();
        BigDecimal avg7 = inv.getAvg7Days() == null ? BigDecimal.ZERO : inv.getAvg7Days();
        BigDecimal avg30 = inv.getAvg30Days() == null ? BigDecimal.ZERO : inv.getAvg30Days();

        BigDecimal daily7 = avg7.divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
        BigDecimal daily30 = avg30.divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        BigDecimal maxDaily = daily7.max(daily30);

        BigDecimal dos;
        if (maxDaily.compareTo(BigDecimal.ZERO) <= 0) {
            // 无销量数据，无法计算 DOS，置为占位大值
            dos = DOS_NO_SALES;
        } else {
            dos = BigDecimal.valueOf(available).divide(maxDaily, 2, RoundingMode.HALF_UP);
        }
        inv.setDaysOfSupply(dos);
        inv.setHealthStatus(classifyByDos(dos, available));
        return inv;
    }

    /**
     * 根据 DOS 与可售库存量返回健康度状态字符串。
     *
     * @param dos       库存可供天数
     * @param available 当前可售库存
     * @return STOCKOUT/URGENT/AT_RISK/HEALTHY/OVERSTOCK
     */
    public String classifyByDos(BigDecimal dos, int available) {
        if (available <= 0) {
            return "STOCKOUT";
        }
        if (dos == null) {
            return "HEALTHY";
        }
        if (dos.compareTo(DOS_URGENT) <= 0) {
            return "URGENT";
        }
        if (dos.compareTo(DOS_AT_RISK) <= 0) {
            return "AT_RISK";
        }
        if (dos.compareTo(DOS_HEALTHY) <= 0) {
            return "HEALTHY";
        }
        return "OVERSTOCK";
    }
}
