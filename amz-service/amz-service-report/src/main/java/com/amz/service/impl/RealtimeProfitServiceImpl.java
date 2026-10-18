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
 * SKU 维度利润快照 + FIFO 成本 + 费用智能分摊。
 * <p>
 * <b>§S33.81 快照粒度是「天」不是「小时」</b>：类名带 Realtime、表注释写「小时级」、
 * 控制器路径是 {@code /report/profit/**}，但聚合源 {@code amz_profit_detail} 只有
 * {@code report_date DATE}，没有小时列。此前 {@link #snapshotProfit} 声称按小时窗口聚合，
 * 实际取的是整天明细并在每小时覆写同一条快照——「小时级」从来没有成立过。
 * 现在按数据真实支撑的粒度（天）实现，{@code stat_time} 记当天 00:00。
 * 要恢复小时粒度必须给明细表补小时列，那是数据模型变更，不能靠改查询口径假装。
 */
@Slf4j
@Service
public class RealtimeProfitServiceImpl implements RealtimeProfitService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 趋势回看上限（天）：防止 days 传超大值拉全表。
     * <p>
     * §S33.81 从 168 小时改为 90 天：快照按天存后，「小时」这个单位已不存在，
     * 而 168 天（原 168 小时按天理解）会让单次聚合 168 行。90 天约等于一个季度，
     * 与经营查看习惯对齐，行数也仍在分页上限之内。
     */
    private static final int MAX_TREND_DAYS = 90;

    /**
     * 趋势内部聚合的单次取数上限。
     * <p>
     * 快照按天写入，趋势又按单 SKU 过滤，{@value #MAX_TREND_DAYS} 天窗口内最多约
     * {@value #MAX_TREND_DAYS} 行，远低于 {@link PageRequest#MAX_SIZE}。
     * 这里刻意不开「内部绕过上限」的口子：一旦存在绕过入口，MAX_SIZE 就从硬约束
     * 退化成可协商的建议值，分页保证会在下一个「内部调用」里被悄悄打破。
     */
    private static final int TREND_FETCH_LIMIT = MAX_TREND_DAYS;

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
    private HeadhaulCostCalculator headhaulCostCalculator;
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

        // §S33.81：明细表只有 report_date（DATE），没有小时列 —— 声称的「本小时」窗口
        // 在数据层根本不存在。此前 hourStart/hourEnd 只取到日期，实际聚合的是
        // **该 SKU 当天全部明细行**：同一天重复点「重算快照」，每次都把整天重报一遍，
        // 而快照表按小时存、趋势与汇总再把多条小时快照求和 → 同一天被重复计数。
        // 明细无小时粒度的前提下没有正确的小时分母，所以不再假装是小时快照：
        // 统计口径改为「当天累计」，stat_time 记当天 00:00，与数据能支撑的粒度对齐。
        LocalDate statDay = now.toLocalDate();
        List<ProfitDetail> details = getProfitDetailsInRange(shopId, sku, statDay, statDay);

        ProfitSnapshot snapshot = new ProfitSnapshot();
        snapshot.setShopId(shopId);
        snapshot.setSku(sku);
        snapshot.setAsin(asin != null ? asin : (details.isEmpty() ? null : details.get(0).getAsin()));
        snapshot.setStatTime(statDay.atStartOfDay());

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
            // 退款金额从明细聚合（V2 补列）。此前恒为 0，注释自认「暂置为 0」——
            // 后果是净利公式里的退款扣减永远不生效，净利系统性偏高、退款损失看不到。
            if (d.getRefundCost() != null) refundCost = refundCost.add(d.getRefundCost());
        }

        snapshot.setSalesAmount(salesAmount);
        // §S33.81：明细表没有件数列（units 只在 amz_sales_daily 上，是另一张表），
        // 这里能拿到的只有明细行数，而明细一行对应一个 amazon_order_id —— 所以它
        // 实际是「订单数」而不是「销售件数」。列名叫 sales_quantity 但装的是订单数，
        // 前端表头写「销量」，运营会把 3 件一单的订单读成卖了 1 件。
        // 加注释钉住真实语义；改成件数需要明细表补列或改从 sales_daily 聚合，
        // 那是独立的数据模型变更，不在本次修复范围内。
        snapshot.setSalesQuantity(details.size());
        snapshot.setProductCost(productCost);
        snapshot.setFbaFees(fbaFees);
        snapshot.setReferralFee(referralFee);
        snapshot.setAdvertisingCost(advertisingCost);
        snapshot.setStorageFee(storageFee);
        snapshot.setVatCost(BigDecimal.ZERO); // VAT 按实际国家税率计算，占位
        snapshot.setHeadhaulCost(headhaulCostCalculator.costFor(shopId, sku));
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
        // 同一天会有多个 SKU/ASIN 的快照，stat_time 并列，单列 id 游标无法保证稳定顺序，
        // 必须用 (stat_time, id) 复合游标。
        if (req.hasCursor()) {
            Object[] key = decodeCursor(req.payload(), v -> LocalDateTime.parse(v, DT_FMT));
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
     * 解析复合游标载荷 {@code <键>|<id>}。
     *
     * <p>快照用 {@code statTime|id}、分摊用 {@code allocDate|id}：两份解析此前是
     * 逐行相同的两份拷贝，只差日期类型（{@code LocalDateTime} / {@code LocalDate}）。
     * 合并后由调用方传入解析器，校验规则（分隔符、id > 0、失败即
     * {@code InvalidParamException}）只剩一份，不会各自漂移。
     *
     * @param parser 把游标前缀解析成时间键的函数；抛运行时异常即视为格式非法
     * @throws InvalidParamException 载荷格式不合法（篡改过的游标，或跨端点混用了别的游标）
     */
    private Object[] decodeCursor(String payload, java.util.function.Function<String, Object> parser) {
        int sep = payload.lastIndexOf(CURSOR_SEP);
        if (sep <= 0 || sep == payload.length() - 1) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        Object key;
        long id;
        try {
            key = parser.apply(payload.substring(0, sep));
            id = Long.parseLong(payload.substring(sep + 1));
        } catch (RuntimeException e) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        if (id <= 0) {
            throw new InvalidParamException("分页游标格式非法：" + payload);
        }
        return new Object[]{key, id};
    }

    @Override
    public Map<String, Object> profitTrend(Long shopId, String sku, String asin, Integer days) {
        if (days == null || days <= 0) days = 7;
        // 封顶：避免超大 days 拉全表；结果里回显封顶后的实际窗口
        if (days > MAX_TREND_DAYS) days = MAX_TREND_DAYS;
        // §S33.81：快照按天存（stat_time 记当天 00:00），趋势窗口必须按天算。
        // 旧实现用 minusHours(days)：快照改成天粒度后 hours=24 只会命中「今天」这
        // 一条（昨天 00:00 落在 24 小时窗口之外），hours=168 只返回 7 条而不是 7 天意图，
        // 而前端输入框写的是「趋势小时」——用户填 24 以为看一天，实际也只能看一天，
        // 填 168 以为看一周，实际看到的是过去 7 个自然日中「今天」之后的那几天。
        // 小时窗口与天粒度数据混用，窗口边界随调用时刻漂移，同一天不同时间点结果不同。
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        String since = from.atStartOfDay().format(DT_FMT);
        String until = today.plusDays(1).atStartOfDay().format(DT_FMT);
        PageResult<ProfitSnapshot> snapshotPage = listSnapshots(shopId, sku, since, until,
                PageRequest.first(TREND_FETCH_LIMIT));
        List<ProfitSnapshot> snapshots = snapshotPage.items();
        if (snapshotPage.truncated()) {
            log.warn("利润趋势聚合被截断：shopId={} sku={} days={}，仅聚合前 {} 条快照",
                    shopId, sku, days, TREND_FETCH_LIMIT);
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
        result.put("days", days);
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
        headhaulCostCalculator.evictShop(allocation.getShopId());
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
            Object[] key = decodeCursor(req.payload(), LocalDate::parse);
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
            headhaulCostCalculator.evictShop(shopId);
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

    /**
     * 按「天」取某 SKU 的利润明细。
     * <p>
     * §S33.81：签名刻意收成 {@link LocalDate} 而不是时间串。{@code amz_profit_detail}
     * 只有 {@code report_date DATE}，没有小时列 —— 传 {@code "2026-10-17 10:00:00"}
     * 进来再 {@code substring(0, 10)} 会给人「这里支持小时过滤」的错觉，
     * 上一个版本正是这么写的，于是「本小时快照」实际聚合了整天明细。
     * 参数类型必须与数据能表达的粒度一致，否则错误的前提会被签名掩盖。
     */
    private List<ProfitDetail> getProfitDetailsInRange(Long shopId, String sku, LocalDate from, LocalDate to) {
        LambdaQueryWrapper<ProfitDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitDetail::getShopId, shopId)
               .eq(ProfitDetail::getSku, sku)
               .ge(ProfitDetail::getReportDate, from)
               .le(ProfitDetail::getReportDate, to);
        return profitDetailMapper.selectList(wrapper);
    }
}
