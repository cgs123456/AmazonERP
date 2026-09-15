package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.*;
import com.amz.model.*;
import com.amz.service.LogisticsUpgradeService;
import com.amz.util.BizNoGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 物流升级服务实现。
 * <p>
 * 覆盖：物流商比价 / 库存调拨 / 头程费用分摊 / 签收差异处理。
 * <p>
 * <b>三条贯穿本类的规则：</b>
 * <ol>
 *   <li><b>归属校验</b>：凡按业务主键（id）定位的单据，实现内部一律再校验一次店铺归属。
 *       {@code @ShopScoped} 切面读的是请求参数里的 shopId，对 {@code /transfer/{id}/approve}
 *       这类没有 shopId 的路径不生效——少了这一步就是横向越权。</li>
 *   <li><b>状态流转白名单</b>：单据状态只允许沿既定路径前进，非法跳转直接报错而不是默默写库。
 *       状态字段一旦被写坏，看板与统计口径会一起失真，且事后无法从数据本身发现。</li>
 *   <li><b>金额守恒</b>：分摊类计算保证「各行之和 = 总额」，尾差由基准量最大的一行吸收。</li>
 * </ol>
 */
@Slf4j
@Service
public class LogisticsUpgradeServiceImpl implements LogisticsUpgradeService {

    // ==================== 常量 ====================

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String QUOTE_EXPIRED = "EXPIRED";

    // ---- 调拨单状态机 ----
    private static final String TRANSFER_DRAFT = "DRAFT";
    private static final String TRANSFER_PENDING = "PENDING_APPROVAL";
    private static final String TRANSFER_APPROVED = "APPROVED";
    private static final String TRANSFER_IN_TRANSIT = "IN_TRANSIT";
    private static final String TRANSFER_RECEIVED = "RECEIVED";
    private static final String TRANSFER_CANCELLED = "CANCELLED";

    /** 可被「审批通过」的源状态 */
    private static final Set<String> TRANSFER_APPROVABLE = Set.of(TRANSFER_DRAFT, TRANSFER_PENDING);

    /** 可被「驳回 / 取消」的源状态：已发出或已收货的单不可取消 */
    private static final Set<String> TRANSFER_CANCELLABLE = Set.of(TRANSFER_DRAFT, TRANSFER_PENDING, TRANSFER_APPROVED);

    /** 全部合法状态，用于写入前校验 */
    private static final Set<String> TRANSFER_STATUSES = Set.of(
            TRANSFER_DRAFT, TRANSFER_PENDING, TRANSFER_APPROVED,
            TRANSFER_IN_TRANSIT, TRANSFER_RECEIVED, TRANSFER_CANCELLED);

    // ---- 分摊 ----
    private static final String METHOD_WEIGHT = "WEIGHT";
    private static final String METHOD_VOLUME = "VOLUME";
    private static final String METHOD_QUANTITY = "QUANTITY";

    private static final Set<String> ALLOCATION_METHODS = Set.of(METHOD_WEIGHT, METHOD_VOLUME, METHOD_QUANTITY);

    // ---- 签收差异 ----
    private static final String TYPE_OVERRECEIVED = "OVERRECEIVED";
    private static final String TYPE_UNDERRECEIVED = "UNDERRECEIVED";
    private static final String TYPE_MATCHED = "MATCHED";

    private static final String DISC_PENDING = "PENDING";
    private static final String DISC_INVESTIGATING = "INVESTIGATING";
    private static final String DISC_RESOLVED = "RESOLVED";

    /** 可被结案的源状态 */
    private static final Set<String> DISC_RESOLVABLE = Set.of(DISC_PENDING, DISC_INVESTIGATING);

    // ==================== 依赖 ====================

    @Autowired
    private CarrierQuoteMapper carrierQuoteMapper;

    @Autowired
    private InventoryTransferMapper inventoryTransferMapper;

    @Autowired
    private FreightAllocationMapper freightAllocationMapper;

    @Autowired
    private FbaReceiptDiscrepancyMapper fbaReceiptDiscrepancyMapper;

    @Autowired
    private ShipmentMapper shipmentMapper;

    // ==================== 物流商比价 ====================

    @Override
    public CarrierQuote saveQuote(CarrierQuote quote) {
        if (quote == null) {
            throw new CodeErrorException("报价内容不能为空");
        }
        // shopId 来自请求体，切面覆盖不到，必须显式校验，否则可以往别家店铺写报价
        requireShopAllowed(quote.getShopId(), "报价");
        if (isBlank(quote.getCarrierName())) {
            throw new CodeErrorException("承运商名称不能为空");
        }
        // service_type 在库中是 NOT NULL：不校验就会撞成数据库约束异常，前端只看到「服务器内部错误」
        if (isBlank(quote.getServiceType())) {
            throw new CodeErrorException("运输方式不能为空（SEA/AIR/EXPRESS/TRUCK）");
        }
        if (quote.getTransitDays() != null && quote.getTransitDays() < 0) {
            throw new CodeErrorException("预计运输天数不能为负数");
        }
        if (quote.getEffectiveDate() != null && quote.getExpiryDate() != null
                && quote.getExpiryDate().isBefore(quote.getEffectiveDate())) {
            throw new CodeErrorException("失效日期不能早于生效日期");
        }
        if (quote.getPricePerKg() == null && quote.getPricePerCbm() == null) {
            // 两个单价都空，比价时算不出任何金额；放进去只会让选商结果缺行且原因不明
            throw new CodeErrorException("每公斤价与每立方米价至少填写一项，否则无法参与比价");
        }
        if (quote.getStatus() == null) {
            quote.setStatus(STATUS_ACTIVE);
        }
        if (quote.getCurrency() == null || quote.getCurrency().isBlank()) {
            quote.setCurrency("USD");
        }
        if (quote.getFuelSurchargeRate() == null) {
            quote.setFuelSurchargeRate(BigDecimal.ZERO);
        }
        carrierQuoteMapper.insert(quote);
        return quote;
    }

    @Override
    public List<CarrierQuote> listQuotes(Long shopId, String serviceType) {
        return carrierQuoteMapper.selectList(new LambdaQueryWrapper<CarrierQuote>()
                .eq(CarrierQuote::getShopId, shopId)
                .eq(CarrierQuote::getStatus, STATUS_ACTIVE)
                .eq(serviceType != null && !serviceType.isBlank(), CarrierQuote::getServiceType, serviceType)
                // 有效期内才算有效报价：只看 status 会让早已失效的运价继续参与选商
                .and(w -> w.isNull(CarrierQuote::getExpiryDate)
                        .or().ge(CarrierQuote::getExpiryDate, LocalDate.now())));
    }

    @Override
    public Map<String, Object> compareQuotes(Long shopId, String originPort, String destinationPort,
                                             BigDecimal weightKg, BigDecimal volumeCbm) {
        if (weightKg == null && volumeCbm == null) {
            throw new CodeErrorException("请至少提供重量或体积，否则无法计算运费");
        }
        if (weightKg != null && weightKg.compareTo(BigDecimal.ZERO) < 0) {
            throw new CodeErrorException("重量不能为负数");
        }
        if (volumeCbm != null && volumeCbm.compareTo(BigDecimal.ZERO) < 0) {
            throw new CodeErrorException("体积不能为负数");
        }

        // 全量取回本店同航线的报价，再在内存里区分「有效 / 已过期」——
        // 直接把过期条件写进 SQL 就没法在返回里说明「有几条被排除了」，
        // 使用者会因为看到承运商少了而怀疑报价丢了。
        LambdaQueryWrapper<CarrierQuote> wrapper = new LambdaQueryWrapper<CarrierQuote>()
                .eq(CarrierQuote::getShopId, shopId)
                .eq(CarrierQuote::getStatus, STATUS_ACTIVE);
        if (originPort != null && !originPort.isBlank()) {
            wrapper.eq(CarrierQuote::getOriginPort, originPort);
        }
        if (destinationPort != null && !destinationPort.isBlank()) {
            wrapper.eq(CarrierQuote::getDestinationPort, destinationPort);
        }
        List<CarrierQuote> all = carrierQuoteMapper.selectList(wrapper);

        LocalDate today = LocalDate.now();
        List<CarrierQuote> expired = new ArrayList<>();
        List<CarrierQuote> valid = new ArrayList<>();
        for (CarrierQuote q : all) {
            if (q.getExpiryDate() != null && q.getExpiryDate().isBefore(today)) {
                expired.add(q);
            } else {
                valid.add(q);
            }
        }

        List<Map<String, Object>> comparisons = new ArrayList<>(valid.size());
        for (CarrierQuote q : valid) {
            comparisons.add(buildComparison(q, weightKg, volumeCbm));
        }

        // 不同币种的金额不可直接比大小，故先按币种分组，组内再按总价升序
        Map<String, List<Map<String, Object>>> byCurrency = new LinkedHashMap<>();
        for (Map<String, Object> c : comparisons) {
            byCurrency.computeIfAbsent((String) c.get("currency"), k -> new ArrayList<>()).add(c);
        }
        byCurrency.values().forEach(list -> list.sort(
                Comparator.comparing(c -> (BigDecimal) c.get("totalCost"))));

        Map<String, String> recommendedByCurrency = new LinkedHashMap<>();
        byCurrency.forEach((currency, list) -> {
            if (!list.isEmpty()) {
                recommendedByCurrency.put(currency, (String) list.get(0).get("carrierName"));
            }
        });

        List<String> warnings = new ArrayList<>();
        if (expired.size() > 0) {
            warnings.add("已排除 " + expired.size() + " 条已过失效日期的报价，不计入比价");
        }
        if (byCurrency.size() > 1) {
            warnings.add("本航线存在多种币种（" + String.join(" / ", byCurrency.keySet())
                    + "），金额不可直接比较，已按币种分别排序");
        }
        if (byCurrency.isEmpty()) {
            warnings.add("该航线没有有效报价，请先补充报价或延长报价有效期");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("originPort", originPort);
        result.put("destinationPort", destinationPort);
        result.put("weightKg", weightKg);
        result.put("volumeCbm", volumeCbm);
        // 明确告知计费口径，避免使用者以为「只按重量算」而漏算体积重
        result.put("billingBasis", "按重量价与体积价取高，再叠加最低收费与燃油附加费");
        result.put("quotes", comparisons);
        result.put("byCurrency", byCurrency);
        result.put("recommendedByCurrency", recommendedByCurrency);
        // 仅在同币种时才给「全局推荐」；跨币种给出单一最优是错的结论
        result.put("recommended", byCurrency.size() == 1 ? recommendedByCurrency.values().iterator().next() : null);
        result.put("excludedExpiredCount", expired.size());
        result.put("warnings", warnings);
        return result;
    }

    /**
     * 计算单个报价的运费构成。
     * <p>
     * <b>为什么重量价与体积价要取高：</b>承运商的计费规则是「实际重量」与「体积重量」
     * 折算后取其高者收费。旧实现用 if/else 二选一，同时传重量与体积时只按重量算，
     * 会算出一个低于实际账单的价格，选商结论随之失真。
     */
    private Map<String, Object> buildComparison(CarrierQuote q, BigDecimal weightKg, BigDecimal volumeCbm) {
        BigDecimal byWeight = null;
        if (weightKg != null && weightKg.compareTo(BigDecimal.ZERO) > 0
                && q.getPricePerKg() != null && q.getPricePerKg().compareTo(BigDecimal.ZERO) > 0) {
            byWeight = q.getPricePerKg().multiply(weightKg);
        }
        BigDecimal byVolume = null;
        if (volumeCbm != null && volumeCbm.compareTo(BigDecimal.ZERO) > 0
                && q.getPricePerCbm() != null && q.getPricePerCbm().compareTo(BigDecimal.ZERO) > 0) {
            byVolume = q.getPricePerCbm().multiply(volumeCbm);
        }

        String basis;
        BigDecimal freightCost;
        if (byWeight != null && byVolume != null) {
            if (byWeight.compareTo(byVolume) >= 0) {
                freightCost = byWeight;
                basis = "WEIGHT";
            } else {
                freightCost = byVolume;
                basis = "VOLUME";
            }
        } else if (byWeight != null) {
            freightCost = byWeight;
            basis = "WEIGHT";
        } else if (byVolume != null) {
            freightCost = byVolume;
            basis = "VOLUME";
        } else {
            // 该报价缺少可用的单价（或未提供对应计量参数），金额记为 0 并标出原因
            freightCost = BigDecimal.ZERO;
            basis = "NO_PRICING_DATA";
        }
        freightCost = freightCost.setScale(2, RoundingMode.HALF_UP);

        boolean minChargeApplied = false;
        if (q.getMinCharge() != null && freightCost.compareTo(q.getMinCharge()) < 0) {
            freightCost = q.getMinCharge().setScale(2, RoundingMode.HALF_UP);
            minChargeApplied = true;
        }

        BigDecimal fuelSurcharge = BigDecimal.ZERO;
        if (q.getFuelSurchargeRate() != null && q.getFuelSurchargeRate().compareTo(BigDecimal.ZERO) > 0) {
            fuelSurcharge = freightCost.multiply(q.getFuelSurchargeRate())
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("quoteId", q.getId());
        comparison.put("carrierName", q.getCarrierName());
        comparison.put("serviceType", q.getServiceType());
        comparison.put("transitDays", q.getTransitDays());
        comparison.put("currency", q.getCurrency() == null ? "USD" : q.getCurrency());
        comparison.put("expiryDate", q.getExpiryDate());
        comparison.put("chargeableBasis", basis);
        comparison.put("costByWeight", byWeight == null ? null : byWeight.setScale(2, RoundingMode.HALF_UP));
        comparison.put("costByVolume", byVolume == null ? null : byVolume.setScale(2, RoundingMode.HALF_UP));
        comparison.put("minChargeApplied", minChargeApplied);
        comparison.put("fuelSurchargeRate", q.getFuelSurchargeRate());
        comparison.put("freightCost", freightCost);
        comparison.put("fuelSurcharge", fuelSurcharge);
        comparison.put("totalCost", freightCost.add(fuelSurcharge));
        return comparison;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int expireOutdatedQuotes(Long shopId) {
        LocalDate today = LocalDate.now();
        List<CarrierQuote> outdated = carrierQuoteMapper.selectList(new LambdaQueryWrapper<CarrierQuote>()
                .eq(CarrierQuote::getShopId, shopId)
                .eq(CarrierQuote::getStatus, STATUS_ACTIVE)
                .isNotNull(CarrierQuote::getExpiryDate)
                .lt(CarrierQuote::getExpiryDate, today));
        if (outdated.isEmpty()) {
            return 0;
        }
        for (CarrierQuote q : outdated) {
            q.setStatus(QUOTE_EXPIRED);
            carrierQuoteMapper.updateById(q);
        }
        log.info("过期报价批量置为 EXPIRED：userId={} shopId={} 条数={}",
                UserContext.getUserId(), shopId, outdated.size());
        return outdated.size();
    }

    // ==================== 库存调拨 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryTransfer createTransfer(InventoryTransfer transfer) {
        if (transfer == null) {
            throw new CodeErrorException("调拨单内容不能为空");
        }
        requireShopAllowed(transfer.getShopId(), "调拨单");
        if (isBlank(transfer.getAsin())) {
            throw new CodeErrorException("ASIN 不能为空");
        }
        // sku 在库中为 NOT NULL，不校验会直接抛数据库约束异常
        if (isBlank(transfer.getSku())) {
            throw new CodeErrorException("SKU 不能为空");
        }
        if (transfer.getQuantity() == null || transfer.getQuantity() <= 0) {
            throw new CodeErrorException("调拨数量必须大于 0");
        }
        if (transfer.getFromWarehouseId() == null || transfer.getToWarehouseId() == null) {
            throw new CodeErrorException("源仓库与目标仓库不能为空");
        }
        if (transfer.getFromWarehouseId().equals(transfer.getToWarehouseId())) {
            throw new CodeErrorException("源仓库与目标仓库不能相同");
        }
        if (transfer.getStatus() == null) {
            transfer.setStatus(TRANSFER_DRAFT);
        }
        if (!TRANSFER_STATUSES.contains(transfer.getStatus())) {
            throw new CodeErrorException("调拨单状态非法：" + transfer.getStatus());
        }
        if (transfer.getShippingCost() == null) {
            transfer.setShippingCost(BigDecimal.ZERO);
        }
        transfer.setTransferNo(BizNoGenerator.next("TRF"));
        inventoryTransferMapper.insert(transfer);
        return transfer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryTransfer approveTransfer(Long transferId, boolean approved) {
        InventoryTransfer transfer = requireTransfer(transferId);
        String current = normalizeStatus(transfer.getStatus());
        String target = approved ? TRANSFER_APPROVED : TRANSFER_CANCELLED;

        if (target.equals(current)) {
            // 幂等：重复点击审批不应报错，也不应再写一次库
            return transfer;
        }
        Set<String> allowed = approved ? TRANSFER_APPROVABLE : TRANSFER_CANCELLABLE;
        if (!allowed.contains(current)) {
            throw new CodeErrorException("调拨单当前状态为「" + current + "」，不可"
                    + (approved ? "审批通过" : "驳回") + "（已发出或已收货的单据不能改判）");
        }
        log.info("调拨单审批：userId={} transferId={} {} → {}",
                UserContext.getUserId(), transferId, current, target);
        transfer.setStatus(target);
        inventoryTransferMapper.updateById(transfer);
        return transfer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryTransfer shipTransfer(Long transferId, String carrier, String trackingNo) {
        InventoryTransfer transfer = requireTransfer(transferId);
        String current = normalizeStatus(transfer.getStatus());

        if (TRANSFER_IN_TRANSIT.equals(current)) {
            // 已在途：允许补充 / 更正承运商与运单号（手误录错时唯一的修正入口），状态不变
            log.info("调拨单在途信息更正：userId={} transferId={}", UserContext.getUserId(), transferId);
        } else if (TRANSFER_APPROVED.equals(current)) {
            transfer.setStatus(TRANSFER_IN_TRANSIT);
            log.info("调拨单发出：userId={} transferId={}", UserContext.getUserId(), transferId);
        } else {
            throw new CodeErrorException("调拨单当前状态为「" + current + "」，只有已审批（APPROVED）的单据才能确认发出");
        }
        if (!isBlank(carrier)) {
            transfer.setCarrier(carrier);
        }
        if (!isBlank(trackingNo)) {
            transfer.setTrackingNo(trackingNo);
        }
        inventoryTransferMapper.updateById(transfer);
        return transfer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryTransfer receiveTransfer(Long transferId) {
        InventoryTransfer transfer = requireTransfer(transferId);
        String current = normalizeStatus(transfer.getStatus());
        if (TRANSFER_RECEIVED.equals(current)) {
            return transfer;
        }
        if (!TRANSFER_IN_TRANSIT.equals(current)) {
            // 之前任何状态都能直接收货，等于「货没发就算到货」，库存账会凭空多出来
            throw new CodeErrorException("调拨单当前状态为「" + current + "」，只有运输中（IN_TRANSIT）的单据才能确认到货");
        }
        log.info("调拨单到货：userId={} transferId={}", UserContext.getUserId(), transferId);
        transfer.setStatus(TRANSFER_RECEIVED);
        inventoryTransferMapper.updateById(transfer);
        return transfer;
    }

    @Override
    public List<InventoryTransfer> listTransfers(Long shopId, String status) {
        LambdaQueryWrapper<InventoryTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryTransfer::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(InventoryTransfer::getStatus, status);
        }
        wrapper.orderByDesc(InventoryTransfer::getId);
        return inventoryTransferMapper.selectList(wrapper);
    }

    // ==================== 头程费用分摊 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<FreightAllocation> saveAllocations(List<FreightAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            throw new CodeErrorException("分摊明细不能为空");
        }
        for (FreightAllocation a : allocations) {
            requireShopAllowed(a.getShopId(), "分摊明细");
            if (a.getShipmentId() == null) {
                throw new CodeErrorException("分摊明细分摊必须关联货件");
            }
            if (isBlank(a.getAsin()) || isBlank(a.getSku())) {
                throw new CodeErrorException("分摊明细的 ASIN 与 SKU 不能为空");
            }
            if (a.getQuantity() == null || a.getQuantity() <= 0) {
                throw new CodeErrorException("分摊数量必须大于 0");
            }
            if (isBlank(a.getAllocationMethod())) {
                a.setAllocationMethod(METHOD_WEIGHT);
            }
            a.setTotalCost(zeroIfNull(a.getFreightCost())
                    .add(zeroIfNull(a.getDutyCost()))
                    .add(zeroIfNull(a.getInsuranceCost()))
                    .add(zeroIfNull(a.getOtherCost())));
            a.setUnitCost(a.getTotalCost().divide(BigDecimal.valueOf(a.getQuantity()), 2, RoundingMode.HALF_UP));
            freightAllocationMapper.insert(a);
        }
        return allocations;
    }

    @Override
    public List<FreightAllocation> listAllocations(Long shipmentId) {
        // 分摊明细本身带 shop_id，但入口只有 shipmentId；
        // 先校验货件归属，避免用他店 shipmentId 读到成本构成（成本是敏感的定价信息）
        requireShipment(shipmentId);
        return freightAllocationMapper.selectList(new LambdaQueryWrapper<FreightAllocation>()
                .eq(FreightAllocation::getShipmentId, shipmentId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> calculateFreightAllocation(Long shipmentId, String method,
                                                          BigDecimal totalFreight, BigDecimal totalDuty,
                                                          BigDecimal totalInsurance) {
        requireShipment(shipmentId);

        // 方法名统一大写并白名单校验：旧实现任何拼错的方法名都会静默按 WEIGHT 算，
        // 使用者以为按体积分摊了，实际是按重量，且没有任何迹象可循
        String normalizedMethod = isBlank(method) ? METHOD_WEIGHT : method.trim().toUpperCase(Locale.ROOT);
        if (!ALLOCATION_METHODS.contains(normalizedMethod)) {
            throw new CodeErrorException("分摊方法非法：" + method + "（可选 WEIGHT / VOLUME / QUANTITY）");
        }

        List<FreightAllocation> allocations = freightAllocationMapper.selectList(
                new LambdaQueryWrapper<FreightAllocation>().eq(FreightAllocation::getShipmentId, shipmentId));
        if (allocations.isEmpty()) {
            throw new CodeErrorException("该货件尚未登记分摊明细，无法计算头程费用");
        }

        // 计算分摊基准与比例
        BigDecimal totalBase = BigDecimal.ZERO;
        for (FreightAllocation a : allocations) {
            totalBase = totalBase.add(baseOf(a, normalizedMethod));
        }
        if (totalBase.compareTo(BigDecimal.ZERO) == 0) {
            throw new CodeErrorException("分摊基准总量为 0（缺少重量 / 体积 / 数量），无法计算");
        }

        List<BigDecimal> ratios = new ArrayList<>(allocations.size());
        for (FreightAllocation a : allocations) {
            ratios.add(baseOf(a, normalizedMethod).divide(totalBase, 8, RoundingMode.HALF_UP));
        }

        BigDecimal freightTotal = scale2(zeroIfNull(totalFreight));
        BigDecimal dutyTotal = scale2(zeroIfNull(totalDuty));
        BigDecimal insuranceTotal = scale2(zeroIfNull(totalInsurance));

        List<BigDecimal> freightParts = allocateWithResidual(freightTotal, ratios);
        List<BigDecimal> dutyParts = allocateWithResidual(dutyTotal, ratios);
        List<BigDecimal> insuranceParts = allocateWithResidual(insuranceTotal, ratios);

        BigDecimal otherTotal = BigDecimal.ZERO;
        for (FreightAllocation a : allocations) {
            otherTotal = otherTotal.add(zeroIfNull(a.getOtherCost()));
        }

        for (int i = 0; i < allocations.size(); i++) {
            FreightAllocation a = allocations.get(i);
            a.setFreightCost(freightParts.get(i));
            a.setDutyCost(dutyParts.get(i));
            a.setInsuranceCost(insuranceParts.get(i));
            a.setOtherCost(scale2(zeroIfNull(a.getOtherCost())));
            a.setTotalCost(a.getFreightCost().add(a.getDutyCost())
                    .add(a.getInsuranceCost()).add(a.getOtherCost()));
            if (a.getQuantity() != null && a.getQuantity() > 0) {
                a.setUnitCost(a.getTotalCost().divide(BigDecimal.valueOf(a.getQuantity()), 2, RoundingMode.HALF_UP));
            }
            a.setAllocationMethod(normalizedMethod);
            freightAllocationMapper.updateById(a);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipmentId", shipmentId);
        result.put("method", normalizedMethod);
        result.put("totalFreight", freightTotal);
        result.put("totalDuty", dutyTotal);
        result.put("totalInsurance", insuranceTotal);
        result.put("totalOther", otherTotal);
        result.put("grandTotal", freightTotal.add(dutyTotal).add(insuranceTotal).add(otherTotal));
        result.put("allocations", allocations);
        // 显式暴露校验量：调用方可以自行确认「分摊之和 = 总额」，不必再手工核一遍
        result.put("allocatedFreight", sum(freightParts));
        result.put("allocatedDuty", sum(dutyParts));
        result.put("allocatedInsurance", sum(insuranceParts));
        result.put("balanced", sum(freightParts).compareTo(freightTotal) == 0
                && sum(dutyParts).compareTo(dutyTotal) == 0
                && sum(insuranceParts).compareTo(insuranceTotal) == 0);
        return result;
    }

    /**
     * 按比例分摊一笔总额，并把四舍五入尾差补给基准量最大的一行。
     * <p>
     * 逐行 {@code setScale(2)} 之后各行之和通常不等于总额（差 1~N 分）。
     * 差额落到基准最大的行：该行金额最大，相对误差最小，也最容易被人工核对接受；
     * 比「塞给最后一行」更稳定（行序变化不会让差额随机漂移）。
     * <p>
     * 假设：{@code ratios} 之和为 1（由调用方以 8 位精度计算比例保证）。
     */
    private List<BigDecimal> allocateWithResidual(BigDecimal total, List<BigDecimal> ratios) {
        List<BigDecimal> parts = new ArrayList<>(ratios.size());
        for (BigDecimal ratio : ratios) {
            parts.add(total.multiply(ratio).setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal allocated = sum(parts);
        BigDecimal residual = total.subtract(allocated);
        if (residual.compareTo(BigDecimal.ZERO) != 0) {
            int largest = 0;
            for (int i = 1; i < ratios.size(); i++) {
                if (ratios.get(i).compareTo(ratios.get(largest)) > 0) {
                    largest = i;
                }
            }
            parts.set(largest, parts.get(largest).add(residual));
        }
        return parts;
    }

    private BigDecimal baseOf(FreightAllocation a, String method) {
        switch (method) {
            case METHOD_VOLUME:
                return zeroIfNull(a.getVolumeCbm());
            case METHOD_QUANTITY:
                return a.getQuantity() == null ? BigDecimal.ZERO : BigDecimal.valueOf(a.getQuantity());
            default:
                return zeroIfNull(a.getWeightKg());
        }
    }

    // ==================== FBA 签收差异 ====================

    @Override
    public FbaReceiptDiscrepancy saveDiscrepancy(FbaReceiptDiscrepancy discrepancy) {
        if (discrepancy == null) {
            throw new CodeErrorException("签收差异内容不能为空");
        }
        requireShopAllowed(discrepancy.getShopId(), "签收差异");
        if (discrepancy.getShipmentId() == null) {
            throw new CodeErrorException("签收差异必须关联货件");
        }
        if (isBlank(discrepancy.getAsin()) || isBlank(discrepancy.getSku())) {
            throw new CodeErrorException("签收差异的 ASIN 与 SKU 不能为空");
        }
        // 旧实现直接 int diff = getDifference()：请求体不传 difference 就拆箱 NPE（返回 500），
        // 而这三个字段在库中都是 NOT NULL，必须在这里兜住
        Integer expected = discrepancy.getExpectedQuantity();
        Integer received = discrepancy.getReceivedQuantity();
        if (expected == null || received == null) {
            throw new CodeErrorException("应收数量与实收数量不能为空");
        }
        if (expected < 0 || received < 0) {
            throw new CodeErrorException("应收 / 实收数量不能为负数");
        }

        // 差异由两个数量算出，不接受调用方自报：自报值可能与两个数量互相矛盾，
        // 一旦落库，看板统计与原始数据就对不上了
        int diff = received - expected;
        discrepancy.setDifference(diff);
        if (isBlank(discrepancy.getDiscrepancyType())) {
            if (diff > 0) {
                discrepancy.setDiscrepancyType(TYPE_OVERRECEIVED);
            } else if (diff < 0) {
                discrepancy.setDiscrepancyType(TYPE_UNDERRECEIVED);
            } else {
                // 0 差异也要留痕：对账结论本身是有效信息，且能让看板区分「没对账」与「对账无差异」
                discrepancy.setDiscrepancyType(TYPE_MATCHED);
            }
        }
        if (discrepancy.getStatus() == null) {
            discrepancy.setStatus(TYPE_MATCHED.equals(discrepancy.getDiscrepancyType()) ? DISC_RESOLVED : DISC_PENDING);
        }
        fbaReceiptDiscrepancyMapper.insert(discrepancy);
        return discrepancy;
    }

    @Override
    public List<FbaReceiptDiscrepancy> listDiscrepancies(Long shopId, String status) {
        LambdaQueryWrapper<FbaReceiptDiscrepancy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FbaReceiptDiscrepancy::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(FbaReceiptDiscrepancy::getStatus, status);
        }
        wrapper.orderByDesc(FbaReceiptDiscrepancy::getId);
        return fbaReceiptDiscrepancyMapper.selectList(wrapper);
    }

    @Override
    public FbaReceiptDiscrepancy startInvestigating(Long discrepancyId) {
        FbaReceiptDiscrepancy d = requireDiscrepancy(discrepancyId);
        String current = normalizeStatus(d.getStatus());
        if (DISC_INVESTIGATING.equals(current) || DISC_RESOLVED.equals(current)) {
            return d;
        }
        if (!DISC_PENDING.equals(current)) {
            throw new CodeErrorException("差异记录当前状态为「" + current + "」，不可转为核查中");
        }
        log.info("签收差异转入核查：userId={} discrepancyId={}", UserContext.getUserId(), discrepancyId);
        d.setStatus(DISC_INVESTIGATING);
        fbaReceiptDiscrepancyMapper.updateById(d);
        return d;
    }

    @Override
    public FbaReceiptDiscrepancy resolveDiscrepancy(Long discrepancyId, String resolution) {
        FbaReceiptDiscrepancy d = requireDiscrepancy(discrepancyId);
        String current = normalizeStatus(d.getStatus());
        if (DISC_RESOLVED.equals(current)) {
            // 幂等：重复结案直接返回，不覆盖已有的处理结果
            return d;
        }
        if (isBlank(resolution)) {
            // 结案必须写结论：否则差异记录会变成一条没有下文的数据，后续无法复盘
            throw new CodeErrorException("结案必须填写处理结果");
        }
        if (!DISC_RESOLVABLE.contains(current)) {
            throw new CodeErrorException("差异记录当前状态为「" + current + "」，不可结案");
        }
        log.info("签收差异结案：userId={} discrepancyId={} 原状态={}",
                UserContext.getUserId(), discrepancyId, current);
        d.setStatus(DISC_RESOLVED);
        d.setResolution(resolution.trim());
        fbaReceiptDiscrepancyMapper.updateById(d);
        return d;
    }

    // ==================== 归属与校验工具 ====================

    /**
     * 校验请求体里的 shopId 是否属于当前用户。
     * <p>
     * {@code @ShopScoped} 切面只认 {@code @RequestParam / @PathVariable}，
     * 请求体中的 shopId 需要在这里兜住，否则可以往他店写报价 / 调拨单 / 差异记录。
     * 信任模型与切面保持一致：{@code UserContext} 无 shops 时跳过（内部调用、AI 工具）。
     */
    private void requireShopAllowed(Long shopId, String what) {
        if (shopId == null) {
            throw new CodeErrorException(what + "缺少店铺 ID");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            log.warn("{}写入越权拦截：userId={} 目标店铺={}", what, UserContext.getUserId(), shopId);
            throw new CodeErrorException(what + "不属于当前账号可操作的店铺");
        }
    }

    /**
     * 按 ID 取调拨单并校验归属。
     * <p>
     * 提示语不区分「不存在」与「无权访问」，否则可用该接口探测他店调拨单是否存在。
     */
    private InventoryTransfer requireTransfer(Long transferId) {
        if (transferId == null) {
            throw new CodeErrorException("调拨单 ID 不能为空");
        }
        InventoryTransfer transfer = inventoryTransferMapper.selectById(transferId);
        if (transfer == null || !UserContext.isShopAllowed(transfer.getShopId())) {
            log.warn("调拨单越权访问拦截：userId={} transferId={}", UserContext.getUserId(), transferId);
            throw new CodeErrorException("调拨单不存在或无权访问");
        }
        return transfer;
    }

    /** 按 ID 取货件并校验归属（头程分摊入口只有 shipmentId，必须先落到店铺上） */
    private Shipment requireShipment(Long shipmentId) {
        if (shipmentId == null) {
            throw new CodeErrorException("货件 ID 不能为空");
        }
        Shipment shipment = shipmentMapper.selectById(shipmentId);
        if (shipment == null || !UserContext.isShopAllowed(shipment.getShopId())) {
            log.warn("货件越权访问拦截（头程分摊）：userId={} shipmentId={}",
                    UserContext.getUserId(), shipmentId);
            throw new CodeErrorException("货件不存在或无权访问");
        }
        return shipment;
    }

    private FbaReceiptDiscrepancy requireDiscrepancy(Long discrepancyId) {
        if (discrepancyId == null) {
            throw new CodeErrorException("差异记录 ID 不能为空");
        }
        FbaReceiptDiscrepancy d = fbaReceiptDiscrepancyMapper.selectById(discrepancyId);
        if (d == null || !UserContext.isShopAllowed(d.getShopId())) {
            log.warn("签收差异越权访问拦截：userId={} discrepancyId={}",
                    UserContext.getUserId(), discrepancyId);
            throw new CodeErrorException("差异记录不存在或无权访问");
        }
        return d;
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale2(BigDecimal value) {
        return zeroIfNull(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(v);
        }
        return total;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
