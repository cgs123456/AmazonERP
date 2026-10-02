package com.amz.service.impl;

import com.amz.exception.InvalidParamException;
import com.amz.mapper.CostAllocationMapper;
import com.amz.mapper.ProfitDetailMapper;
import com.amz.mapper.ProfitSnapshotMapper;
import com.amz.model.CostAllocation;
import com.amz.model.ProfitDetail;
import com.amz.model.ProfitSnapshot;
import com.amz.util.MapArgUtils;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.RealtimeProfitService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实时利润核算服务实现。
 * <p>
 * 按小时更新 SKU 维度利润快照 + FIFO 成本 + 费用智能分摊。
 */
@Slf4j
@Service
public class RealtimeProfitServiceImpl implements RealtimeProfitService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 趋势回看上限（小时）：防止 hours 传超大值拉全表。 */
    private static final int MAX_TREND_HOURS = 168;

    /**
     * 趋势内部聚合的单次取数上限。
     * <p>
     * 快照按小时写入，趋势又按单 SKU 过滤，168 小时窗口内最多约 168 行，
     * 远低于 {@link PageRequest#MAX_SIZE}。这里刻意不开「内部绕过上限」的口子：
     * 一旦存在绕过入口，MAX_SIZE 就从硬约束退化成可协商的建议值，
     * 分页保证会在下一个「内部调用」里被悄悄打破。
     */
    private static final int TREND_FETCH_LIMIT = MAX_TREND_HOURS;

    /** 头程分摊扫描上限：触顶即告警，不再静默丢弃更旧的分摊记录。 */
    private static final int HEADHAUL_SCAN_LIMIT = 200;

    /** 头程分摊回看天数：超出窗口的分摊视为已摊销完毕，不参与当前小时利润。 */
    private static final long HEADHAUL_LOOKBACK_DAYS = 90L;

    /** 复合游标分隔符：载荷形如 {@code 2026-09-27 10:00:00|123}。 */
    private static final String CURSOR_SEP = "|";

    @Autowired
    private ProfitSnapshotMapper profitSnapshotMapper;
    @Autowired
    private ProfitDetailMapper profitDetailMapper;
    @Autowired
    private CostAllocationMapper costAllocationMapper;
    @Autowired
    private ReportTenantGuard tenantGuard;
    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 利润快照 ====================

    @Override
    public ProfitSnapshot snapshotProfit(Long shopId, String sku, String asin) {
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);

        // 查重：同一 shopId+sku+statTime 覆盖
        LambdaQueryWrapper<ProfitSnapshot> existWrapper = new LambdaQueryWrapper<>();
        existWrapper.eq(ProfitSnapshot::getShopId, shopId)
                    .eq(ProfitSnapshot::getSku, sku)
                    .eq(ProfitSnapshot::getStatTime, now);
        ProfitSnapshot exist = profitSnapshotMapper.selectOne(existWrapper);

        // 从 ProfitDetail 聚合本小时数据
        String hourStart = now.format(DT_FMT);
        String hourEnd = now.plusHours(1).format(DT_FMT);
        List<ProfitDetail> details = getProfitDetailsInRange(shopId, sku, hourStart, hourEnd);

        ProfitSnapshot snapshot = new ProfitSnapshot();
        snapshot.setShopId(shopId);
        snapshot.setSku(sku);
        snapshot.setAsin(asin != null ? asin : (details.isEmpty() ? null : details.get(0).getAsin()));
        snapshot.setStatTime(now);

        // 从利润明细聚合
        BigDecimal salesAmount = BigDecimal.ZERO;
        BigDecimal productCost = BigDecimal.ZERO;
        BigDecimal fbaFees = BigDecimal.ZERO;
        BigDecimal referralFee = BigDecimal.ZERO;
        BigDecimal advertisingCost = BigDecimal.ZERO;
        BigDecimal storageFee = BigDecimal.ZERO;
        BigDecimal refundCost = BigDecimal.ZERO;

        for (ProfitDetail d : details) {
            if (d.getProductSales() != null) salesAmount = salesAmount.add(d.getProductSales());
            if (d.getProductCost() != null) productCost = productCost.add(d.getProductCost());
            if (d.getFbaFees() != null) fbaFees = fbaFees.add(d.getFbaFees());
            if (d.getReferralFee() != null) referralFee = referralFee.add(d.getReferralFee());
            if (d.getAdvertisingCost() != null) advertisingCost = advertisingCost.add(d.getAdvertisingCost());
            if (d.getStorageFee() != null) storageFee = storageFee.add(d.getStorageFee());
            // ProfitDetail 无退款字段，退款从 otherFees 中按业务规则拆分，此处暂置为 0
        }

        snapshot.setSalesAmount(salesAmount);
        snapshot.setSalesQuantity(details.size());
        snapshot.setProductCost(productCost);
        snapshot.setFbaFees(fbaFees);
        snapshot.setReferralFee(referralFee);
        snapshot.setAdvertisingCost(advertisingCost);
        snapshot.setStorageFee(storageFee);
        snapshot.setVatCost(BigDecimal.ZERO); // VAT 按实际国家税率计算，占位
        snapshot.setHeadhaulCost(getHeadhaulForSku(shopId, sku));
        snapshot.setRefundCost(refundCost);
        snapshot.setOtherCost(BigDecimal.ZERO);

        // 毛利 = 销售额 - 采购 - FBA - 佣金
        BigDecimal grossProfit = salesAmount.subtract(productCost).subtract(fbaFees).subtract(referralFee);
        snapshot.setGrossProfit(grossProfit);

        // 净利 = 毛利 - 广告 - VAT - 仓储 - 头程 - 退款 - 其他
        BigDecimal totalDeduct = advertisingCost.add(snapshot.getVatCost()).add(storageFee)
                .add(snapshot.getHeadhaulCost()).add(refundCost).add(snapshot.getOtherCost());
        snapshot.setNetProfit(grossProfit.subtract(totalDeduct));

        // 利润率
        snapshot.setMargin(salesAmount.compareTo(BigDecimal.ZERO) > 0
                ? snapshot.getNetProfit().multiply(new BigDecimal("100")).divide(salesAmount, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);

        snapshot.setDataSource("CALC");

        // upsert
        if (exist != null) {
            snapshot.setId(exist.getId());
            profitSnapshotMapper.updateById(snapshot);
        } else {
            profitSnapshotMapper.insert(snapshot);
        }
        log.debug("利润快照更新：shopId={} sku={} sales={} netProfit={}", shopId, sku, salesAmount, snapshot.getNetProfit());
        return snapshot;
    }

    @Override
    public PageResult<ProfitSnapshot> listSnapshots(Long shopId, String sku, String startTime, String endTime,
                                                    PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<ProfitSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitSnapshot::getShopId, shopId);
        if (sku != null && !sku.isBlank()) wrapper.eq(ProfitSnapshot::getSku, sku);
        if (startTime != null) wrapper.ge(ProfitSnapshot::getStatTime, LocalDateTime.parse(startTime, DT_FMT));
        if (endTime != null) wrapper.le(ProfitSnapshot::getStatTime, LocalDateTime.parse(endTime, DT_FMT));
        // 同一小时可能有多个 SKU/ASIN 的快照，stat_time 并列，单列 id 游标无法保证稳定顺序，
        // 必须用 (stat_time, id) 复合游标。
        if (req.hasCursor()) {
            Object[] key = decodeSnapshotCursor(req.payload());
            wrapper.apply("(stat_time < {0} OR (stat_time = {0} AND id < {1}))", key[0], key[1]);
        }
        wrapper.orderByDesc(ProfitSnapshot::getStatTime)
               .orderByDesc(ProfitSnapshot::getId);
        // 探测行：多取 1 行判定 hasMore，不额外 COUNT(*)；LIMIT 由常量拼接，无注入面
        wrapper.last("LIMIT " + req.probeSize());
        List<ProfitSnapshot> rows = profitSnapshotMapper.selectList(wrapper);
        PageResult<ProfitSnapshot> result = PageResult.of(rows, req.size(), this::snapshotCursor);
        if (result.truncated()) {
            log.warn("利润快照列表被分页截断：shopId={} sku={} size={}，请用 nextCursor 继续翻页",
                    shopId, sku, req.size());
        }
        return result;
    }

    /** 由本页最后一行生成下一页游标：(stat_time, id) 复合键。 */
    private String snapshotCursor(ProfitSnapshot snapshot) {
        return PageRequest.encodeCursor(snapshot.getStatTime().format(DT_FMT) + CURSOR_SEP + snapshot.getId());
    }

    /**
     * 解析快照游标载荷 {@code statTime|id}。
     *
     * @throws InvalidParamException 载荷格式不合法（篡改过的游标，或跨端点混用了别的游标）
     */
    private Object[] decodeSnapshotCursor(String payload) {
        int sep = payload.lastIndexOf(CURSOR_SEP);
        if (sep <= 0 || sep == payload.length() - 1) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        LocalDateTime statTime;
        long id;
        try {
            statTime = LocalDateTime.parse(payload.substring(0, sep), DT_FMT);
            id = Long.parseLong(payload.substring(sep + 1));
        } catch (RuntimeException e) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        if (id <= 0) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        return new Object[]{statTime, id};
    }

    @Override
    public Map<String, Object> profitTrend(Long shopId, String sku, String asin, Integer hours) {
        if (hours == null || hours <= 0) hours = 24;
        // B2：回看窗口封顶，避免超大 hours 拉全表；结果中的 hours 回显封顶后的实际窗口
        if (hours > MAX_TREND_HOURS) hours = MAX_TREND_HOURS;
        String since = LocalDateTime.now().minusHours(hours).format(DT_FMT);
        String until = LocalDateTime.now().format(DT_FMT);
        PageResult<ProfitSnapshot> snapshotPage = listSnapshots(shopId, sku, since, until,
                PageRequest.first(TREND_FETCH_LIMIT));
        List<ProfitSnapshot> snapshots = snapshotPage.items();
        if (snapshotPage.truncated()) {
            log.warn("利润趋势聚合被截断：shopId={} sku={} hours={}，仅聚合前 {} 条快照",
                    shopId, sku, hours, TREND_FETCH_LIMIT);
        }

        List<Map<String, Object>> trendData = snapshots.stream().map(s -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", s.getStatTime().format(DT_FMT));
            point.put("sales", s.getSalesAmount());
            point.put("grossProfit", s.getGrossProfit());
            point.put("netProfit", s.getNetProfit());
            point.put("margin", s.getMargin());
            return point;
        }).collect(Collectors.toList());
        Collections.reverse(trendData); // 时间升序

        // 汇总
        BigDecimal totalSales = snapshots.stream().map(s -> s.getSalesAmount() != null ? s.getSalesAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalNetProfit = snapshots.stream().map(s -> s.getNetProfit() != null ? s.getNetProfit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("sku", sku);
        result.put("hours", hours);
        result.put("snapshotCount", snapshots.size());
        // 截断必须机器可读：调用方不能把「窗口内还有快照没聚合进来」当成完整趋势
        result.put("trendTruncated", snapshotPage.truncated());
        result.put("totalSales", totalSales);
        result.put("totalNetProfit", totalNetProfit);
        result.put("trendData", trendData);
        return result;
    }

    @Override
    public Map<String, Object> profitSummary(Long shopId, String startTime, String endTime) {
        // B2：聚合下推 SQL（GROUP BY sku），内存占用从 O(快照行数) 降到 O(SKU 数）；
        // 响应结构与逐行版严格一致（含 asin 取组内 MAX、netProfit 降序）。
        List<Map<String, Object>> groups = profitSnapshotMapper.sumBySku(shopId, startTime, endTime);

        List<Map<String, Object>> skuSummaries = new ArrayList<>();
        BigDecimal totalSales = BigDecimal.ZERO, totalNet = BigDecimal.ZERO;
        for (Map<String, Object> g : groups) {
            if (g == null) {
                continue;
            }
            BigDecimal sSales = toBigDecimal(g.get("sales"));
            BigDecimal sNet = toBigDecimal(g.get("netProfit"));
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("sku", g.get("sku"));
            sm.put("asin", g.get("asin"));
            sm.put("sales", sSales);
            sm.put("netProfit", sNet);
            sm.put("margin", sSales.compareTo(BigDecimal.ZERO) > 0
                    ? sNet.multiply(new BigDecimal("100")).divide(sSales, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            sm.put("snapshotCount", g.get("snapshotCount"));
            skuSummaries.add(sm);
            totalSales = totalSales.add(sSales);
            totalNet = totalNet.add(sNet);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("totalSales", totalSales);
        result.put("totalNetProfit", totalNet);
        result.put("overallMargin", totalSales.compareTo(BigDecimal.ZERO) > 0
                ? totalNet.multiply(new BigDecimal("100")).divide(totalSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        result.put("skuCount", skuSummaries.size());
        result.put("skuSummaries", skuSummaries);
        return result;
    }

    // ==================== 费用分摊 ====================

    @Override
    public CostAllocation saveAllocation(CostAllocation allocation) {
        tenantGuard.requireShopAccess(allocation.getShopId(), "费用分摊");
        if (allocation.getAllocDate() == null) allocation.setAllocDate(LocalDate.now());
        costAllocationMapper.insert(allocation);
        evictHeadhaulCache(allocation.getShopId());
        log.info("费用分摊记录创建：costType={} amount={} method={}", allocation.getCostType(),
                allocation.getTotalAmount(), allocation.getAllocMethod());
        return allocation;
    }

    @Override
    public PageResult<CostAllocation> listAllocations(Long shopId, String costType, String startDate, String endDate,
                                                      PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<CostAllocation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CostAllocation::getShopId, shopId);
        if (costType != null && !costType.isBlank()) wrapper.eq(CostAllocation::getCostType, costType);
        if (startDate != null) wrapper.ge(CostAllocation::getAllocDate, LocalDate.parse(startDate));
        if (endDate != null) wrapper.le(CostAllocation::getAllocDate, LocalDate.parse(endDate));
        // 同一天可能有多条分摊记录，故同样用 (alloc_date, id) 复合游标
        if (req.hasCursor()) {
            Object[] key = decodeAllocCursor(req.payload());
            wrapper.apply("(alloc_date < {0} OR (alloc_date = {0} AND id < {1}))", key[0], key[1]);
        }
        wrapper.orderByDesc(CostAllocation::getAllocDate)
               .orderByDesc(CostAllocation::getId);
        // 此端点此前完全没有上限（P1-02 无界列表），一并纳入统一分页
        wrapper.last("LIMIT " + req.probeSize());
        List<CostAllocation> rows = costAllocationMapper.selectList(wrapper);
        PageResult<CostAllocation> result = PageResult.of(rows, req.size(), this::allocCursor);
        if (result.truncated()) {
            log.warn("费用分摊列表被分页截断：shopId={} costType={} size={}", shopId, costType, req.size());
        }
        return result;
    }

    /** 由本页最后一行生成下一页游标：(alloc_date, id) 复合键。 */
    private String allocCursor(CostAllocation allocation) {
        return PageRequest.encodeCursor(allocation.getAllocDate() + CURSOR_SEP + allocation.getId());
    }

    private Object[] decodeAllocCursor(String payload) {
        int sep = payload.lastIndexOf(CURSOR_SEP);
        if (sep <= 0 || sep == payload.length() - 1) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        LocalDate allocDate;
        long id;
        try {
            allocDate = LocalDate.parse(payload.substring(0, sep));
            id = Long.parseLong(payload.substring(sep + 1));
        } catch (RuntimeException e) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        if (id <= 0) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        return new Object[]{allocDate, id};
    }

    @Override
    public Map<String, BigDecimal> allocateCost(Long shopId, String costType, BigDecimal totalAmount,
                                                List<String> entries, String sourceRef, String currency) {
        if (shopId == null) {
            throw new InvalidParamException("shopId 不能为空");
        }
        if (costType == null || costType.isBlank()) {
            throw new InvalidParamException("costType 不能为空");
        }
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new InvalidParamException("分摊总额必须大于 0，实际 " + totalAmount);
        }
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyMap();
        }

        // 带金额 = 按给定金额入账（来自采购域已摊好的明细）；全不带 = 均摊
        Map<String, BigDecimal> explicit = new LinkedHashMap<>();
        List<String> plainSkus = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            int sep = entry.indexOf(':');
            if (sep < 0) {
                if (explicit.containsKey(entry) || plainSkus.contains(entry)) {
                    throw new InvalidParamException("SKU 重复出现在分摊列表中：" + entry);
                }
                plainSkus.add(entry);
                continue;
            }
            String sku = entry.substring(0, sep).trim();
            String amountText = entry.substring(sep + 1).trim();
            if (sku.isEmpty() || amountText.isEmpty()) {
                throw new InvalidParamException("分摊项格式非法，需为 SKU 或 SKU:金额，实际 " + raw);
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(amountText);
            } catch (NumberFormatException e) {
                throw new InvalidParamException("分摊金额不是数字：" + raw + "（" + e.getMessage() + "）");
            }
            if (amount.signum() < 0) {
                throw new InvalidParamException("分摊金额不能为负：" + raw);
            }
            if (explicit.containsKey(sku)) {
                throw new InvalidParamException("SKU 重复出现在分摊列表中：" + sku);
            }
            explicit.put(sku, amount);
        }
        if (!explicit.isEmpty() && !plainSkus.isEmpty()) {
            throw new InvalidParamException("分摊项不能混用「带金额」与「只写 SKU」两种写法");
        }

        Map<String, BigDecimal> allocation;
        String allocMethod;
        if (!explicit.isEmpty()) {
            BigDecimal sum = explicit.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            if (sum.compareTo(totalAmount.setScale(2, RoundingMode.HALF_UP)) != 0) {
                // 静默补差等于凭空多算或少算成本，必须拒绝入账
                throw new InvalidParamException("给定分摊金额合计 " + sum.toPlainString()
                        + " 与总额 " + totalAmount.toPlainString() + " 不一致，拒绝入账");
            }
            allocation = explicit;
            allocMethod = "EXPLICIT";
        } else {
            // 均摊法：除不尽的尾差给最后一个 SKU，保证合计严格等于总额
            BigDecimal perUnit = totalAmount.divide(new BigDecimal(plainSkus.size()), 4, RoundingMode.HALF_UP);
            BigDecimal remaining = totalAmount;
            allocation = new LinkedHashMap<>();
            for (int i = 0; i < plainSkus.size(); i++) {
                if (i == plainSkus.size() - 1) {
                    allocation.put(plainSkus.get(i), remaining);
                } else {
                    allocation.put(plainSkus.get(i), perUnit);
                    remaining = remaining.subtract(perUnit);
                }
            }
            allocMethod = "EVEN";
        }

        boolean hasSource = sourceRef != null && !sourceRef.isBlank();
        if (hasSource && costAllocationMapper.countBySource(shopId, costType, sourceRef) > 0) {
            CostAllocation stored = costAllocationMapper.selectBySource(shopId, costType, sourceRef);
            Map<String, BigDecimal> storedDetails = parseStoredDetails(stored);
            log.info("同来源分摊已存在，幂等跳过：shopId={} costType={} sourceRef={}", shopId, costType, sourceRef);
            // 返回账上真正记着的明细，而不是本次算出来的：两者不一致时以账为准
            return storedDetails.isEmpty() ? allocation : storedDetails;
        }

        try {
            CostAllocation alloc = new CostAllocation();
            alloc.setShopId(shopId);
            alloc.setCostType(costType);
            alloc.setSourceRef(hasSource ? sourceRef : null);
            alloc.setCurrency(currency);
            alloc.setTotalAmount(totalAmount);
            alloc.setAllocMethod(allocMethod);
            alloc.setAllocDetails(objectMapper.writeValueAsString(allocation));
            alloc.setAllocDate(LocalDate.now());
            costAllocationMapper.insert(alloc);
            evictHeadhaulCache(shopId);
        } catch (JsonProcessingException e) {
            // 明细序列化不了就不要留一条「有总额、没分摊」的半成品记录
            throw new IllegalStateException("分摊明细序列化失败 costType=" + costType + "：" + e.getMessage(), e);
        }
        return allocation;
    }

    /** 读回已入账的分摊明细；解析不了返回空 Map，由调用方决定退化到什么。 */
    private Map<String, BigDecimal> parseStoredDetails(CostAllocation stored) {
        if (stored == null || stored.getAllocDetails() == null || stored.getAllocDetails().isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(stored.getAllocDetails(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? BigDecimal.ZERO : new BigDecimal(v.toString())));
            return out;
        } catch (Exception e) {
            log.warn("已入账分摊明细无法解析，按本次计算值返回：{}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 聚合值为 null 安全转 BigDecimal（SUM 全 NULL 时 JDBC 返回 null）。
     */
    private static BigDecimal toBigDecimal(Object value) {
        return MapArgUtils.toBigDecimal(value, BigDecimal.ZERO);
    }

    private List<ProfitDetail> getProfitDetailsInRange(Long shopId, String sku, String startTime, String endTime) {
        LambdaQueryWrapper<ProfitDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitDetail::getShopId, shopId)
               .eq(ProfitDetail::getSku, sku)
               .ge(ProfitDetail::getReportDate, LocalDate.parse(startTime.substring(0, 10)))
               .le(ProfitDetail::getReportDate, LocalDate.parse(endTime.substring(0, 10)));
        return profitDetailMapper.selectList(wrapper);
    }

    /** 头程费用缓存 TTL：分摊记录变更低频，短 TTL 即可过滤批量快照的重复解析。 */
    private static final long HEADHAUL_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 头程费用缓存上限（条目数，LRU 淘汰）。 */
    private static final int HEADHAUL_CACHE_MAX_SIZE = 1024;

    /**
     * 头程费用缓存：key 为 shopId + sku，value 为 [缓存值, 过期时间戳]。
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

    /**
     * 估算 SKU 的头程费用（从 CostAllocation 聚合，带 5 分钟 TTL 缓存）。
     */
    private BigDecimal getHeadhaulForSku(Long shopId, String sku) {
        // key 带分隔符：无分隔拼接下店铺 1 的前缀失效会误删店铺 12 的条目
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
    private void evictHeadhaulCache(Long shopId) {
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
