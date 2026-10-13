package com.amz.service.impl;

import com.amz.dto.PlatformOrderStats;
import com.amz.mapper.UnifiedOrderMapper;
import com.amz.model.UnifiedOrder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 多平台订单的分平台统计（A8）。
 *
 * <p>口径见 {@link PlatformOrderStats}：按平台分组、金额按币种分组，
 * 不产跨平台合计。
 *
 * <p><b>有界读</b>：每个平台最多扫 {@link #MAX_ROWS_PER_PLATFORM} 行，
 * 触顶即标记 truncated——统计是全表扫描类操作，不加上限会在数据量上来后
 * 拖垮接口；但截断必须披露，否则「1600 单的统计」会被当成「全部订单的统计」。
 */
@Slf4j
@Service
public class PlatformOrderStatsService {

    /** 单个平台参与统计的最大行数。 */
    public static final int MAX_ROWS_PER_PLATFORM = 5_000;

    @Autowired
    private UnifiedOrderMapper unifiedOrderMapper;

    /**
     * 按店铺统计多平台订单。
     *
     * @param platforms 只看这些平台；为空表示全部已接入平台
     */
    public PlatformOrderStats stats(Long shopId, List<String> platforms) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId 不能为空");
        }
        PlatformOrderStats result = new PlatformOrderStats();

        List<String> targets = (platforms == null || platforms.isEmpty())
                ? discoverPlatforms(shopId)
                : platforms;

        int totalOrders = 0;
        for (String platform : targets) {
            PlatformOrderStats.PlatformBucket bucket = bucketOf(shopId, platform);
            result.getPlatforms().add(bucket);
            totalOrders += bucket.getOrderCount();
            if (bucket.isTruncated()) {
                result.getTruncatedPlatforms().add(platform);
            }
        }
        result.setTotalOrders(totalOrders);
        return result;
    }

    /** 该店铺实际存在哪些平台（不靠外部枚举，避免列出没数据的平台）。 */
    private List<String> discoverPlatforms(Long shopId) {
        List<UnifiedOrder> probe = unifiedOrderMapper.selectList(
                new LambdaQueryWrapper<UnifiedOrder>()
                        .eq(UnifiedOrder::getShopId, shopId)
                        .select(UnifiedOrder::getPlatform)
                        .last("LIMIT " + (MAX_ROWS_PER_PLATFORM + 1)));
        // 用 TreeSet 语义排序，让输出稳定（Map 遍历顺序不稳定会让报告每次看起来不一样）
        java.util.Set<String> set = new java.util.TreeSet<>();
        for (UnifiedOrder o : probe) {
            if (o.getPlatform() != null && !o.getPlatform().isBlank()) {
                set.add(o.getPlatform().trim());
            }
        }
        return new ArrayList<>(set);
    }

    private PlatformOrderStats.PlatformBucket bucketOf(Long shopId, String platform) {
        PlatformOrderStats.PlatformBucket bucket = new PlatformOrderStats.PlatformBucket();
        bucket.setPlatform(platform);

        List<UnifiedOrder> rows = unifiedOrderMapper.selectList(
                new LambdaQueryWrapper<UnifiedOrder>()
                        .eq(UnifiedOrder::getShopId, shopId)
                        .eq(UnifiedOrder::getPlatform, platform)
                        .last("LIMIT " + (MAX_ROWS_PER_PLATFORM + 1)));

        // 多取一行用来判断是否触顶，不把它算进统计
        if (rows.size() > MAX_ROWS_PER_PLATFORM) {
            bucket.setTruncated(true);
            rows = rows.subList(0, MAX_ROWS_PER_PLATFORM);
        }
        bucket.setOrderCount(rows.size());

        Map<String, BigDecimal> amountByCurrency = new LinkedHashMap<>();
        Map<String, BigDecimal> cnyByCurrency = new LinkedHashMap<>();
        Map<String, Integer> statusCounts = new TreeMap<>();

        for (UnifiedOrder o : rows) {
            String currency = o.getCurrency() == null || o.getCurrency().isBlank()
                    ? "UNKNOWN" : o.getCurrency().trim();
            if (o.getOriginalAmount() != null) {
                amountByCurrency.merge(currency, o.getOriginalAmount(), BigDecimal::add);
            }
            if (o.getCnyAmount() != null) {
                cnyByCurrency.merge(currency, o.getCnyAmount(), BigDecimal::add);
            }
            String status = o.getStatus() == null || o.getStatus().isBlank()
                    ? "UNKNOWN" : o.getStatus().trim();
            statusCounts.merge(status, 1, Integer::sum);
        }
        bucket.setAmountByCurrency(amountByCurrency);
        bucket.setCnyByCurrency(cnyByCurrency);
        bucket.getStatusCounts().putAll(statusCounts);
        return bucket;
    }
}
