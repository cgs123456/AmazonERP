package com.amz.service.impl;

import com.amz.mapper.CostAllocationMapper;
import com.amz.model.CostAllocation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKU 头程费用计算器：从 {@code amz_cost_allocation} 聚合该 SKU 的头程分摊，带 TTL 缓存。
 * <p>
 * 从 {@link RealtimeProfitServiceImpl} 拆出（§S33.81）：它是「读分摊账 + 单机缓存」
 * 这一独立职责，缓存失效、LRU、扫描窗口告警的不变式都自成一界；拆出后
 * 快照服务专注「明细 → 快照」的聚合口径。拆分是 ratchet 逼出来的，
 * 但拆出来的接缝本身就是对的——分摊写入方（saveAllocation/allocateCost）与
 * 读取方（快照）之间只需要 {@link #costFor} 与 {@link #evictShop} 两个方法。
 */
@Slf4j
@Component
public class HeadhaulCostCalculator {

    /** 头程分摊扫描上限：触顶即告警，不再静默丢弃更旧的分摊记录。 */
    private static final int HEADHAUL_SCAN_LIMIT = 200;

    /** 头程分摊回看天数：超出窗口的分摊视为已摊销完毕，不参与当前利润。 */
    private static final long HEADHAUL_LOOKBACK_DAYS = 90L;

    /** 头程费用缓存 TTL：分摊记录变更低频，短 TTL 即可过滤批量快照的重复解析。 */
    private static final long HEADHAUL_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 头程费用缓存上限（条目数，LRU 淘汰）。 */
    private static final int HEADHAUL_CACHE_MAX_SIZE = 1024;

    /**
     * 头程费用缓存：key 为 shopId + "|" + sku，value 为 [缓存值, 过期时间戳]。
     * <p>
     * 不引入 Caffeine 依赖的原因：此处仅需单机小容量 TTL 缓存，
     * 同步 LinkedHashMap（access-order LRU）已够用，避免为 report 模块新增依赖。
     */
    private final Map<String, Object[]> headhaulCache =
            Collections.synchronizedMap(new LinkedHashMap<String, Object[]>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
                    return size() > HEADHAUL_CACHE_MAX_SIZE;
                }
            });

    @Autowired
    private CostAllocationMapper costAllocationMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 估算 SKU 的头程费用（从 CostAllocation 聚合，带 5 分钟 TTL 缓存）。
     */
    public BigDecimal costFor(Long shopId, String sku) {
        // key 带分隔符：无分隔拼接下店铺 1 的缓存会误命中/误删店铺 12 的条目
        String key = shopId + "|" + sku;
        long now = System.currentTimeMillis();
        Object[] cached = headhaulCache.get(key);
        if (cached != null && (Long) cached[1] > now) {
            return (BigDecimal) cached[0];
        }
        BigDecimal total = loadHeadhaulForSku(shopId, sku);
        headhaulCache.put(key, new Object[]{total, now + HEADHAUL_CACHE_TTL_MS});
        return total;
    }

    /**
     * 分摊记录变更后失效对应店铺的头程缓存（saveAllocation / allocateCost 成功后调用）。
     */
    public void evictShop(Long shopId) {
        if (shopId == null) {
            return;
        }
        String prefix = shopId + "|";
        // synchronizedMap 的迭代（含 removeIf）必须外部加锁，否则并发 get/put+evict 可抛 CME
        synchronized (headhaulCache) {
            headhaulCache.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    private BigDecimal loadHeadhaulForSku(Long shopId, String sku) {
        // 从分摊记录中查找该 SKU 最近头程费用
        LambdaQueryWrapper<CostAllocation> wrapper = new LambdaQueryWrapper<>();
        // 此前是「按 allocDate 倒序取 10 条」，既没有时间窗口也没有触顶告警：
        // 分摊记录一旦超过 10 条，更旧的头程成本会被静默丢弃，利润被高估而不留痕迹。
        // 现在改为「90 天回看窗口 + 200 条上限 + 触顶 WARN」，把隐式截断变成显式信号。
        wrapper.eq(CostAllocation::getShopId, shopId)
                .eq(CostAllocation::getCostType, "HEADHAUL")
                .ge(CostAllocation::getAllocDate, LocalDate.now().minusDays(HEADHAUL_LOOKBACK_DAYS))
                .orderByDesc(CostAllocation::getAllocDate)
                .last("LIMIT " + HEADHAUL_SCAN_LIMIT);
        List<CostAllocation> allocations = costAllocationMapper.selectList(wrapper);
        if (allocations.size() >= HEADHAUL_SCAN_LIMIT) {
            log.warn("头程分摊扫描触顶：shopId={} 近 {} 天内超过 {} 条 HEADHAUL 记录，"
                            + "更早的分摊未计入头程成本，请拆分分摊批次或缩短回看窗口",
                    shopId, HEADHAUL_LOOKBACK_DAYS, HEADHAUL_SCAN_LIMIT);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (CostAllocation a : allocations) {
            if (a.getAllocDetails() != null && a.getAllocDetails().contains("\"" + sku + "\"")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = objectMapper.readValue(a.getAllocDetails(), Map.class);
                    Object val = details.get(sku);
                    if (val instanceof Number) {
                        total = total.add(new BigDecimal(val.toString()));
                    }
                } catch (Exception e) {
                    // 用 debug 等于瞒报：这一行的头程成本被静默丢掉，SKU 利润被系统性高估，
                    // 而生产默认日志级别下没人看得见。同方法上方对"明细被截断"已经是 WARN，此处对齐。
                    log.warn("解析成本分摊详情失败，该条分摊金额未计入：shopId={} sku={} error={}",
                            shopId, sku, e.toString());
                }
            }
        }
        return total;
    }
}
