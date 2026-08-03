package com.amz.service.impl;

import com.amz.mapper.*;
import com.amz.model.*;
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
import java.util.*;
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

    @Autowired
    private ProfitSnapshotMapper profitSnapshotMapper;
    @Autowired
    private ProfitDetailMapper profitDetailMapper;
    @Autowired
    private CostAllocationMapper costAllocationMapper;
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
    public List<ProfitSnapshot> listSnapshots(Long shopId, String sku, String startTime, String endTime) {
        LambdaQueryWrapper<ProfitSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitSnapshot::getShopId, shopId);
        if (sku != null && !sku.isBlank()) wrapper.eq(ProfitSnapshot::getSku, sku);
        if (startTime != null) wrapper.ge(ProfitSnapshot::getStatTime, LocalDateTime.parse(startTime, DT_FMT));
        if (endTime != null) wrapper.le(ProfitSnapshot::getStatTime, LocalDateTime.parse(endTime, DT_FMT));
        wrapper.orderByDesc(ProfitSnapshot::getStatTime);
        return profitSnapshotMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> profitTrend(Long shopId, String sku, String asin, Integer hours) {
        if (hours == null || hours <= 0) hours = 24;
        String since = LocalDateTime.now().minusHours(hours).format(DT_FMT);
        String until = LocalDateTime.now().format(DT_FMT);
        List<ProfitSnapshot> snapshots = listSnapshots(shopId, sku, since, until);

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
        result.put("totalSales", totalSales);
        result.put("totalNetProfit", totalNetProfit);
        result.put("trendData", trendData);
        return result;
    }

    @Override
    public Map<String, Object> profitSummary(Long shopId, String startTime, String endTime) {
        LambdaQueryWrapper<ProfitSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitSnapshot::getShopId, shopId);
        if (startTime != null) wrapper.ge(ProfitSnapshot::getStatTime, LocalDateTime.parse(startTime, DT_FMT));
        if (endTime != null) wrapper.le(ProfitSnapshot::getStatTime, LocalDateTime.parse(endTime, DT_FMT));
        List<ProfitSnapshot> all = profitSnapshotMapper.selectList(wrapper);

        // 按 SKU 聚合
        Map<String, List<ProfitSnapshot>> bySku = all.stream()
                .filter(s -> s.getSku() != null)
                .collect(Collectors.groupingBy(ProfitSnapshot::getSku, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> skuSummaries = new ArrayList<>();
        BigDecimal totalSales = BigDecimal.ZERO, totalNet = BigDecimal.ZERO;
        for (Map.Entry<String, List<ProfitSnapshot>> entry : bySku.entrySet()) {
            List<ProfitSnapshot> group = entry.getValue();
            BigDecimal sSales = group.stream().map(s -> s.getSalesAmount() != null ? s.getSalesAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sNet = group.stream().map(s -> s.getNetProfit() != null ? s.getNetProfit() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("sku", entry.getKey());
            sm.put("asin", group.get(0).getAsin());
            sm.put("sales", sSales);
            sm.put("netProfit", sNet);
            sm.put("margin", sSales.compareTo(BigDecimal.ZERO) > 0
                    ? sNet.multiply(new BigDecimal("100")).divide(sSales, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            sm.put("snapshotCount", group.size());
            skuSummaries.add(sm);
            totalSales = totalSales.add(sSales);
            totalNet = totalNet.add(sNet);
        }

        skuSummaries.sort((a, b) -> ((BigDecimal) b.get("netProfit")).compareTo((BigDecimal) a.get("netProfit")));

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
        if (allocation.getAllocDate() == null) allocation.setAllocDate(LocalDate.now());
        costAllocationMapper.insert(allocation);
        log.info("费用分摊记录创建：costType={} amount={} method={}", allocation.getCostType(),
                allocation.getTotalAmount(), allocation.getAllocMethod());
        return allocation;
    }

    @Override
    public List<CostAllocation> listAllocations(Long shopId, String costType, String startDate, String endDate) {
        LambdaQueryWrapper<CostAllocation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CostAllocation::getShopId, shopId);
        if (costType != null && !costType.isBlank()) wrapper.eq(CostAllocation::getCostType, costType);
        if (startDate != null) wrapper.ge(CostAllocation::getAllocDate, LocalDate.parse(startDate));
        if (endDate != null) wrapper.le(CostAllocation::getAllocDate, LocalDate.parse(endDate));
        wrapper.orderByDesc(CostAllocation::getAllocDate);
        return costAllocationMapper.selectList(wrapper);
    }

    @Override
    public Map<String, BigDecimal> allocateCost(Long shopId, String costType, BigDecimal totalAmount, List<String> skus) {
        // 均摊法（默认）
        if (skus == null || skus.isEmpty()) return Collections.emptyMap();
        BigDecimal perUnit = totalAmount.divide(new BigDecimal(skus.size()), 4, RoundingMode.HALF_UP);

        // 处理除不尽的情况：最后一个 SKU 拿剩余
        BigDecimal remaining = totalAmount;
        Map<String, BigDecimal> allocation = new LinkedHashMap<>();
        for (int i = 0; i < skus.size(); i++) {
            if (i == skus.size() - 1) {
                allocation.put(skus.get(i), remaining);
            } else {
                allocation.put(skus.get(i), perUnit);
                remaining = remaining.subtract(perUnit);
            }
        }

        // 记录分摊
        try {
            CostAllocation alloc = new CostAllocation();
            alloc.setShopId(shopId);
            alloc.setCostType(costType);
            alloc.setTotalAmount(totalAmount);
            alloc.setAllocMethod("EVEN");
            alloc.setAllocDetails(objectMapper.writeValueAsString(allocation));
            alloc.setAllocDate(LocalDate.now());
            costAllocationMapper.insert(alloc);
        } catch (JsonProcessingException e) {
            log.warn("分摊明细序列化失败 costType={}", costType, e);
        }

        return allocation;
    }

    // ==================== 辅助方法 ====================

    private List<ProfitDetail> getProfitDetailsInRange(Long shopId, String sku, String startTime, String endTime) {
        LambdaQueryWrapper<ProfitDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitDetail::getShopId, shopId)
               .eq(ProfitDetail::getSku, sku)
               .ge(ProfitDetail::getReportDate, LocalDate.parse(startTime.substring(0, 10)))
               .le(ProfitDetail::getReportDate, LocalDate.parse(endTime.substring(0, 10)));
        return profitDetailMapper.selectList(wrapper);
    }

    /**
     * 估算 SKU 的头程费用（从 CostAllocation 聚合）。
     */
    private BigDecimal getHeadhaulForSku(Long shopId, String sku) {
        // 从分摊记录中查找该 SKU 最近头程费用
        LambdaQueryWrapper<CostAllocation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CostAllocation::getShopId, shopId)
               .eq(CostAllocation::getCostType, "HEADHAUL")
               .orderByDesc(CostAllocation::getAllocDate)
               .last("LIMIT 10");
        List<CostAllocation> allocations = costAllocationMapper.selectList(wrapper);
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
                } catch (Exception ignored) { }
            }
        }
        return total;
    }
}
