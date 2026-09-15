package com.amz.service.impl;

import com.amz.dto.FreightCostBoard;
import com.amz.dto.QuoteBoard;
import com.amz.dto.ReceiptBoard;
import com.amz.dto.TransferBoard;
import com.amz.mapper.CarrierQuoteMapper;
import com.amz.mapper.FbaReceiptDiscrepancyMapper;
import com.amz.mapper.FreightAllocationMapper;
import com.amz.mapper.InventoryTransferMapper;
import com.amz.mapper.ShipmentMapper;
import com.amz.model.CarrierQuote;
import com.amz.model.FbaReceiptDiscrepancy;
import com.amz.model.FreightAllocation;
import com.amz.model.InventoryTransfer;
import com.amz.model.Shipment;
import com.amz.service.LogisticsOpsDashboardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 物流运营子域看板聚合实现。
 * <p>
 * 与 {@code LogisticsDashboardServiceImpl} 共享同一套写法：
 * 按店铺一次取回、内存聚合、无样本返回 null。
 */
@Slf4j
@Service
public class LogisticsOpsDashboardServiceImpl implements LogisticsOpsDashboardService {

    // ---------------- 状态常量 ----------------

    private static final String QUOTE_ACTIVE = "ACTIVE";
    private static final String QUOTE_EXPIRED = "EXPIRED";
    private static final String QUOTE_DISABLED = "DISABLED";

    private static final String TRANSFER_DRAFT = "DRAFT";
    private static final String TRANSFER_PENDING = "PENDING_APPROVAL";
    private static final String TRANSFER_APPROVED = "APPROVED";
    private static final String TRANSFER_IN_TRANSIT = "IN_TRANSIT";
    private static final String TRANSFER_RECEIVED = "RECEIVED";
    private static final String TRANSFER_CANCELLED = "CANCELLED";

    private static final List<String> ALL_TRANSFER_STATUSES = List.of(
            TRANSFER_DRAFT, TRANSFER_PENDING, TRANSFER_APPROVED,
            TRANSFER_IN_TRANSIT, TRANSFER_RECEIVED, TRANSFER_CANCELLED);

    private static final String DISC_PENDING = "PENDING";
    private static final String DISC_INVESTIGATING = "INVESTIGATING";
    private static final String DISC_RESOLVED = "RESOLVED";

    private static final String SEVERITY_HIGH = "HIGH";
    private static final String SEVERITY_MEDIUM = "MEDIUM";
    private static final String SEVERITY_LOW = "LOW";

    private static final String CURRENCY_MIXED = "MIXED";

    private static final String PORT_UNKNOWN = "未填写";

    /** 航线覆盖返回上限：看板是「看哪儿有问题」，全量航线应由列表页承载 */
    private static final int ROUTE_LIMIT = 50;

    /** 未核算成本货件返回上限 */
    private static final int UNCOVERED_LIMIT = 50;

    /** 单件成本 Top 返回条数 */
    private static final int TOP_UNIT_COST_LIMIT = 20;

    /** 待处理差异返回上限 */
    private static final int PENDING_DISCREPANCY_LIMIT = 100;

    /** 调拨风险清单返回上限 */
    private static final int TRANSFER_RISK_LIMIT = 100;

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

    /** 在途超过该天数未收货即视为卡单 */
    @Value("${amz.logistics.dashboard.transfer-stale-days:7}")
    private int transferStaleDays;

    /** 待审批停留超过该天数即视为卡审批 */
    @Value("${amz.logistics.dashboard.transfer-approval-stale-days:3}")
    private int transferApprovalStaleDays;

    // ================================================================ 报价看板

    @Override
    public QuoteBoard quoteBoard(Long shopId) {
        QuoteBoard board = new QuoteBoard();
        board.setShopId(shopId);

        Map<String, Integer> byServiceType = new LinkedHashMap<>();
        Map<String, Integer> byCurrency = new LinkedHashMap<>();
        Map<String, RouteAccumulator> routeMap = new LinkedHashMap<>();
        Set<String> carriers = new HashSet<>();
        List<String> warnings = new ArrayList<>();

        LocalDate today = LocalDate.now();
        LocalDate expiryWarnDate = today.plusDays(30);

        List<CarrierQuote> quotes = carrierQuoteMapper.selectList(
                new LambdaQueryWrapper<CarrierQuote>().eq(CarrierQuote::getShopId, shopId));
        board.setTotalQuotes(quotes.size());

        int valid = 0;
        int staleByDate = 0;
        int expiringSoon = 0;
        int inactive = 0;
        int unpriced = 0;

        for (CarrierQuote q : quotes) {
            String status = normalize(q.getStatus());
            boolean active = QUOTE_ACTIVE.equals(status);
            if (!active) {
                inactive++;
            } else if (q.getExpiryDate() != null && q.getExpiryDate().isBefore(today)) {
                // 已失效但状态还是 ACTIVE：比价会把它排除，这里单独计数让使用者知道要收口
                staleByDate++;
            } else {
                valid++;
                if (q.getExpiryDate() != null && !q.getExpiryDate().isAfter(expiryWarnDate)) {
                    expiringSoon++;
                }
                carriers.add(q.getCarrierName() == null ? PORT_UNKNOWN : q.getCarrierName());
                byServiceType.merge(orUnknown(q.getServiceType()), 1, Integer::sum);
                byCurrency.merge(orDefault(q.getCurrency(), "USD"), 1, Integer::sum);
                routeMap.computeIfAbsent(routeKey(q.getOriginPort(), q.getDestinationPort()),
                        k -> new RouteAccumulator(q.getOriginPort(), q.getDestinationPort())).add(q);
            }
            // 只统计 ACTIVE 的：已停用的报价「无法参与比价」本就无关紧要，
            // 混进来会把这个数字撑大，反而看不出真正需要补价的那几条
            if (active && q.getPricePerKg() == null && q.getPricePerCbm() == null) {
                unpriced++;
            }
        }

        board.setValidQuotes(valid);
        board.setStaleByDate(staleByDate);
        board.setExpiringIn30Days(expiringSoon);
        board.setInactiveQuotes(inactive);
        board.setUnpricedQuotes(unpriced);
        board.setCarrierCount(carriers.size());
        board.setRouteCount(routeMap.size());
        board.setByServiceType(byServiceType);
        board.setByCurrency(byCurrency);

        // 承运商少的航线排前面：单点供应是选商时最该先处理的问题
        List<QuoteBoard.RouteCoverage> routes = new ArrayList<>(routeMap.size());
        routeMap.values().forEach(acc -> routes.add(acc.toVo()));
        routes.sort(Comparator
                .comparingInt(QuoteBoard.RouteCoverage::getCarrierCount)
                .thenComparing(r -> orEmpty(r.getOriginPort()))
                .thenComparing(r -> orEmpty(r.getDestinationPort())));
        board.setRoutes(truncate(routes, ROUTE_LIMIT, warnings, "航线"));

        long singleSource = routes.stream().filter(QuoteBoard.RouteCoverage::isSingleSource).count();
        if (staleByDate > 0) {
            warnings.add(staleByDate + " 条报价已过失效日期但状态仍为 ACTIVE，比价时会自动排除；可执行「标记过期」收口");
        }
        if (unpriced > 0) {
            warnings.add(unpriced + " 条报价未填写任何单价，无法参与比价");
        }
        if (byCurrency.size() > 1) {
            warnings.add("存在多种币种报价（" + String.join(" / ", byCurrency.keySet()) + "），跨币种金额不可直接比较");
        }
        if (singleSource > 0) {
            warnings.add(singleSource + " 条航线只有一家承运商，没有议价空间");
        }
        if (valid == 0 && !quotes.isEmpty()) {
            warnings.add("当前没有有效报价，比价功能不可用；请补充报价或延长已有报价的有效期");
        }
        if (quotes.isEmpty()) {
            warnings.add("尚未登记任何物流商报价，无法进行运费比价");
        }
        board.setWarnings(warnings);

        log.debug("报价看板聚合完成：shopId={} 总数={} 有效={} 待收口={} 航线={}",
                shopId, quotes.size(), valid, staleByDate, routeMap.size());
        return board;
    }

    /** 单航线累积器：在聚合过程中累计承运商与价格区间，避免二次遍历 */
    private static class RouteAccumulator {

        private final String originPort;
        private final String destinationPort;
        private final Set<String> carriers = new HashSet<>();
        private final Set<String> currencies = new HashSet<>();

        private int minTransitDays = Integer.MAX_VALUE;
        private int maxTransitDays = Integer.MIN_VALUE;
        private BigDecimal minPricePerKg;
        private BigDecimal maxPricePerKg;
        private BigDecimal minPricePerCbm;
        private BigDecimal maxPricePerCbm;

        RouteAccumulator(String originPort, String destinationPort) {
            this.originPort = originPort;
            this.destinationPort = destinationPort;
        }

        void add(CarrierQuote q) {
            carriers.add(q.getCarrierName() == null ? PORT_UNKNOWN : q.getCarrierName());
            currencies.add(orDefault(q.getCurrency(), "USD"));
            if (q.getTransitDays() != null) {
                minTransitDays = Math.min(minTransitDays, q.getTransitDays());
                maxTransitDays = Math.max(maxTransitDays, q.getTransitDays());
            }
            minPricePerKg = min(minPricePerKg, q.getPricePerKg());
            maxPricePerKg = max(maxPricePerKg, q.getPricePerKg());
            minPricePerCbm = min(minPricePerCbm, q.getPricePerCbm());
            maxPricePerCbm = max(maxPricePerCbm, q.getPricePerCbm());
        }

        QuoteBoard.RouteCoverage toVo() {
            QuoteBoard.RouteCoverage vo = new QuoteBoard.RouteCoverage();
            vo.setOriginPort(originPort);
            vo.setDestinationPort(destinationPort);
            vo.setCarrierCount(carriers.size());
            List<String> sorted = new ArrayList<>(carriers);
            sorted.sort(Comparator.naturalOrder());
            vo.setCarriers(sorted);
            vo.setMinTransitDays(minTransitDays == Integer.MAX_VALUE ? -1 : minTransitDays);
            vo.setMaxTransitDays(maxTransitDays == Integer.MIN_VALUE ? -1 : maxTransitDays);
            vo.setMinPricePerKg(minPricePerKg);
            vo.setMaxPricePerKg(maxPricePerKg);
            vo.setMinPricePerCbm(minPricePerCbm);
            vo.setMaxPricePerCbm(maxPricePerCbm);
            vo.setSingleSource(carriers.size() <= 1);

            if (currencies.size() == 1) {
                vo.setCurrency(currencies.iterator().next());
                vo.setPriceSpreadRate(spread(minPricePerKg, maxPricePerKg));
            } else {
                // 多币种时给出统一币种标签会让价格区间被误读，明确标为 MIXED 并放弃溢价率
                vo.setCurrency(CURRENCY_MIXED);
                vo.setPriceSpreadRate(null);
            }
            return vo;
        }

        /** 溢价率 =（最高 - 最低）/ 最低；最低价缺失或为 0 时无意义，返回 null */
        private static BigDecimal spread(BigDecimal min, BigDecimal max) {
            if (min == null || max == null || min.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            return max.subtract(min).divide(min, 4, RoundingMode.HALF_UP);
        }

        private static BigDecimal min(BigDecimal current, BigDecimal candidate) {
            if (candidate == null) {
                return current;
            }
            return current == null || candidate.compareTo(current) < 0 ? candidate : current;
        }

        private static BigDecimal max(BigDecimal current, BigDecimal candidate) {
            if (candidate == null) {
                return current;
            }
            return current == null || candidate.compareTo(current) > 0 ? candidate : current;
        }
    }

    // ================================================================ 调拨看板

    @Override
    public TransferBoard transferBoard(Long shopId) {
        TransferBoard board = new TransferBoard();
        board.setShopId(shopId);
        board.setStaleThresholdDays(transferStaleDays);

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String status : ALL_TRANSFER_STATUSES) {
            byStatus.put(status, 0);
        }
        board.setByStatus(byStatus);

        List<InventoryTransfer> transfers = inventoryTransferMapper.selectList(
                new LambdaQueryWrapper<InventoryTransfer>().eq(InventoryTransfer::getShopId, shopId));
        board.setTotal(transfers.size());
        List<String> warnings = new ArrayList<>();
        if (transfers.isEmpty()) {
            board.setTotalShippingCost(BigDecimal.ZERO);
            board.setRisks(List.of());
            board.setWarnings(warnings);
            return board;
        }

        BigDecimal shippingCost = BigDecimal.ZERO;
        int pendingApproval = 0;
        int approvedNotShipped = 0;
        int inTransit = 0;
        int received = 0;
        int cancelled = 0;
        int staleInTransit = 0;
        List<TransferBoard.TransferRisk> risks = new ArrayList<>();

        for (InventoryTransfer t : transfers) {
            String status = normalize(t.getStatus());
            // merge 而非 put：出现未登记的状态值时新增键而不是丢弃，避免统计漏计
            byStatus.merge(status, 1, Integer::sum);
            shippingCost = shippingCost.add(zeroIfNull(t.getShippingCost()));

            Integer days = daysSince(t.getCreateTime());
            switch (status) {
                case TRANSFER_DRAFT:
                case TRANSFER_PENDING:
                    pendingApproval++;
                    if (days != null && days > transferApprovalStaleDays) {
                        risks.add(risk(t, status, days, SEVERITY_MEDIUM, "PENDING_APPROVAL_TOO_LONG",
                                "已提交 " + days + " 天仍未审批，调拨计划被卡住",
                                "尽快审批或驳回，避免两边仓库的库存计划都停在这里"));
                    }
                    break;
                case TRANSFER_APPROVED:
                    approvedNotShipped++;
                    break;
                case TRANSFER_IN_TRANSIT:
                    inTransit++;
                    if (days != null && days > transferStaleDays) {
                        staleInTransit++;
                        risks.add(risk(t, status, days, SEVERITY_HIGH, "STALE_IN_TRANSIT",
                                "已发出 " + days + " 天仍未确认到货（阈值 " + transferStaleDays + " 天）",
                                "核对调拨物流单号的在途位置，确认是正常在途还是已丢件"));
                    } else if (isBlank(t.getTrackingNo())) {
                        risks.add(risk(t, status, days, SEVERITY_MEDIUM, "MISSING_TRACKING_NO",
                                "已确认发出但未登记物流单号，无法追踪",
                                "补录承运商与物流单号，后续才能自动跟单"));
                    }
                    break;
                case TRANSFER_RECEIVED:
                    received++;
                    break;
                case TRANSFER_CANCELLED:
                    cancelled++;
                    break;
                default:
                    break;
            }
        }

        board.setPendingApproval(pendingApproval);
        board.setApprovedNotShipped(approvedNotShipped);
        board.setInTransit(inTransit);
        board.setReceived(received);
        board.setCancelled(cancelled);
        board.setTotalShippingCost(shippingCost);
        board.setStaleInTransit(staleInTransit);

        // 严重程度优先，同级内停留越久越靠前
        risks.sort(Comparator
                .comparingInt((TransferBoard.TransferRisk r) -> severityWeight(r.getSeverity()))
                .thenComparing(r -> r.getDaysSinceCreated() == null ? 0 : -r.getDaysSinceCreated()));
        board.setRisks(truncate(risks, TRANSFER_RISK_LIMIT, warnings, "待跟进调拨单"));

        if (staleInTransit > 0) {
            warnings.add(staleInTransit + " 张调拨单已发出超过 " + transferStaleDays + " 天仍未确认到货");
        }
        if (approvedNotShipped > 0) {
            warnings.add(approvedNotShipped + " 张调拨单已审批但尚未发出，货还在源仓");
        }
        board.setWarnings(warnings);

        log.debug("调拨看板聚合完成：shopId={} 总数={} 待审批={} 在途={} 卡单={}",
                shopId, transfers.size(), pendingApproval, inTransit, staleInTransit);
        return board;
    }

    private TransferBoard.TransferRisk risk(InventoryTransfer t, String status, Integer days,
                                            String severity, String type, String message, String actionHint) {
        TransferBoard.TransferRisk vo = new TransferBoard.TransferRisk();
        vo.setId(t.getId());
        vo.setTransferNo(t.getTransferNo());
        vo.setFromWarehouseId(t.getFromWarehouseId());
        vo.setToWarehouseId(t.getToWarehouseId());
        vo.setAsin(t.getAsin());
        vo.setSku(t.getSku());
        vo.setQuantity(t.getQuantity());
        vo.setCarrier(t.getCarrier());
        vo.setTrackingNo(t.getTrackingNo());
        vo.setShippingCost(t.getShippingCost());
        vo.setStatus(status);
        vo.setDaysSinceCreated(days);
        vo.setUpdateTime(t.getUpdateTime());
        vo.setType(type);
        vo.setSeverity(severity);
        vo.setMessage(message);
        vo.setActionHint(actionHint);
        return vo;
    }

    // ================================================================ 头程成本看板

    @Override
    public FreightCostBoard freightCostBoard(Long shopId) {
        FreightCostBoard board = new FreightCostBoard();
        board.setShopId(shopId);

        List<Shipment> shipments = shipmentMapper.selectList(
                new LambdaQueryWrapper<Shipment>().eq(Shipment::getShopId, shopId));
        List<FreightAllocation> allocations = freightAllocationMapper.selectList(
                new LambdaQueryWrapper<FreightAllocation>().eq(FreightAllocation::getShopId, shopId));

        board.setTotalAllocationRows(allocations.size());

        BigDecimal freight = BigDecimal.ZERO;
        BigDecimal duty = BigDecimal.ZERO;
        BigDecimal insurance = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        long quantity = 0L;
        Map<String, Integer> methodMix = new LinkedHashMap<>();
        Set<Long> coveredShipmentIds = new HashSet<>();

        for (FreightAllocation a : allocations) {
            // 明细自带 shop_id，但历史数据可能缺失；以额度累加为准，缺失时按 0 计而不是报错，
            // 看板的职责是把问题显示出来，不是拦住一次读取
            freight = freight.add(zeroIfNull(a.getFreightCost()));
            duty = duty.add(zeroIfNull(a.getDutyCost()));
            insurance = insurance.add(zeroIfNull(a.getInsuranceCost()));
            other = other.add(zeroIfNull(a.getOtherCost()));
            if (a.getQuantity() != null && a.getQuantity() > 0) {
                quantity += a.getQuantity();
            }
            if (a.getShipmentId() != null) {
                coveredShipmentIds.add(a.getShipmentId());
            }
            methodMix.merge(isBlank(a.getAllocationMethod()) ? "WEIGHT" : a.getAllocationMethod().toUpperCase(Locale.ROOT),
                    1, Integer::sum);
        }

        BigDecimal totalCost = freight.add(duty).add(insurance).add(other);
        board.setTotalFreight(freight);
        board.setTotalDuty(duty);
        board.setTotalInsurance(insurance);
        board.setTotalOther(other);
        board.setTotalCost(totalCost);
        board.setTotalQuantity(quantity);
        board.setMethodMix(methodMix);
        // 总数量为 0 时给 null：0 单件成本会被读成「成本极低」，实际是「没有可摊的数量」
        board.setAvgUnitCost(quantity > 0
                ? totalCost.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP)
                : null);

        List<FreightCostBoard.UncoveredShipment> uncovered = new ArrayList<>();
        for (Shipment s : shipments) {
            if (s.getId() == null || coveredShipmentIds.contains(s.getId())) {
                continue;
            }
            FreightCostBoard.UncoveredShipment vo = new FreightCostBoard.UncoveredShipment();
            vo.setShipmentId(s.getId());
            vo.setShipmentNo(s.getShipmentNo());
            vo.setCarrier(s.getCarrier());
            vo.setStatus(normalize(s.getStatus()));
            vo.setEta(s.getEta());
            vo.setFreightCost(s.getFreightCost());
            // 货件主单已登记运费却没做分摊，说明是「有费用没摊」而不是「这笔没费用」，
            // 前者会直接导致利润虚高，优先度更高
            vo.setHasDeclaredFreight(s.getFreightCost() != null && s.getFreightCost().compareTo(BigDecimal.ZERO) > 0);
            uncovered.add(vo);
        }
        uncovered.sort(Comparator
                .comparing(FreightCostBoard.UncoveredShipment::isHasDeclaredFreight).reversed()
                .thenComparing(u -> u.getShipmentId() == null ? 0L : -u.getShipmentId()));

        board.setCoveredShipments(coveredShipmentIds.size());
        board.setUncoveredShipments(uncovered.size());
        board.setCoverageRate(shipments.isEmpty()
                ? null
                : BigDecimal.valueOf(coveredShipmentIds.size())
                        .divide(BigDecimal.valueOf(shipments.size()), 4, RoundingMode.HALF_UP));

        List<String> warnings = new ArrayList<>();
        board.setUncoveredList(truncate(uncovered, UNCOVERED_LIMIT, warnings, "未核算货件"));

        List<FreightCostBoard.CostItem> topCost = new ArrayList<>();
        Map<Long, Shipment> shipmentIndex = new HashMap<>();
        for (Shipment s : shipments) {
            if (s.getId() != null) {
                shipmentIndex.put(s.getId(), s);
            }
        }
        for (FreightAllocation a : allocations) {
            if (a.getUnitCost() == null) {
                continue;
            }
            FreightCostBoard.CostItem vo = new FreightCostBoard.CostItem();
            vo.setShipmentId(a.getShipmentId());
            Shipment shipment = a.getShipmentId() == null ? null : shipmentIndex.get(a.getShipmentId());
            vo.setShipmentNo(shipment == null ? null : shipment.getShipmentNo());
            vo.setAsin(a.getAsin());
            vo.setSku(a.getSku());
            vo.setQuantity(a.getQuantity());
            vo.setTotalCost(a.getTotalCost());
            vo.setUnitCost(a.getUnitCost());
            vo.setAllocationMethod(a.getAllocationMethod());
            topCost.add(vo);
        }
        topCost.sort(Comparator.comparing(FreightCostBoard.CostItem::getUnitCost).reversed());
        board.setTopUnitCostItems(truncate(topCost, TOP_UNIT_COST_LIMIT, warnings, "高成本明细"));

        long withDeclaredFreight = uncovered.stream()
                .filter(FreightCostBoard.UncoveredShipment::isHasDeclaredFreight).count();
        if (withDeclaredFreight > 0) {
            // 这一类最要紧：钱已经花了但没摊到商品上，利润会被高估
            warnings.add(withDeclaredFreight + " 个货件已登记运费但未做头程分摊，其运费尚未计入商品成本");
        }
        if (uncovered.size() > withDeclaredFreight) {
            warnings.add((uncovered.size() - withDeclaredFreight) + " 个货件既无分摊明细也无登记运费，头程成本为空");
        }
        if (methodMix.size() > 1) {
            warnings.add("存在多种分摊方法（" + String.join(" / ", methodMix.keySet())
                    + "），跨货件的单件成本不可直接比较");
        }
        if (allocations.isEmpty() && !shipments.isEmpty()) {
            warnings.add("尚无任何头程分摊明细，单件成本无法计算");
        }
        board.setWarnings(warnings);

        log.debug("头程成本看板聚合完成：shopId={} 明细={} 已核算货件={} 未核算={} 总成本={}",
                shopId, allocations.size(), coveredShipmentIds.size(), uncovered.size(), totalCost);
        return board;
    }

    // ================================================================ 签收差异看板

    @Override
    public ReceiptBoard receiptBoard(Long shopId) {
        ReceiptBoard board = new ReceiptBoard();
        board.setShopId(shopId);

        List<FbaReceiptDiscrepancy> rows = fbaReceiptDiscrepancyMapper.selectList(
                new LambdaQueryWrapper<FbaReceiptDiscrepancy>().eq(FbaReceiptDiscrepancy::getShopId, shopId));
        board.setTotal(rows.size());
        List<String> warnings = new ArrayList<>();
        if (rows.isEmpty()) {
            board.setTopShortageAsins(List.of());
            board.setPendingItems(List.of());
            board.setWarnings(warnings);
            return board;
        }

        // 货件号只在待处理清单里展示，按需建立索引即可（单店千级）
        Map<Long, String> shipmentNoIndex = new HashMap<>();
        for (Shipment s : shipmentMapper.selectList(
                new LambdaQueryWrapper<Shipment>().eq(Shipment::getShopId, shopId))) {
            if (s.getId() != null) {
                shipmentNoIndex.put(s.getId(), s.getShipmentNo());
            }
        }

        int pending = 0;
        int investigating = 0;
        int resolved = 0;
        int matched = 0;
        int shortageRows = 0;
        int overreceivedRows = 0;
        long expected = 0L;
        long received = 0L;
        long shortageUnits = 0L;
        long overreceivedUnits = 0L;
        long openShortageUnits = 0L;
        long absDiffSum = 0L;
        Map<String, AsinAccumulator> asinMap = new LinkedHashMap<>();
        List<ReceiptBoard.DiscrepancyItem> pendingItems = new ArrayList<>();

        for (FbaReceiptDiscrepancy d : rows) {
            int diff = d.getDifference() == null ? 0 : d.getDifference();
            String status = normalize(d.getStatus());
            String type = d.getDiscrepancyType();

            switch (status) {
                case DISC_PENDING:
                    pending++;
                    break;
                case DISC_INVESTIGATING:
                    investigating++;
                    break;
                case DISC_RESOLVED:
                    resolved++;
                    break;
                default:
                    break;
            }

            // 差异为 0 的记录不算「差异」，否则差异率与少收件数都会被稀释；
            // 但它仍要计数，用来区分「对过账且无差异」和「还没对账」
            if (diff == 0) {
                matched++;
            } else if (diff < 0) {
                shortageRows++;
                shortageUnits += -diff;
                if (DISC_PENDING.equals(status) || DISC_INVESTIGATING.equals(status)) {
                    openShortageUnits += -diff;
                }
            } else {
                overreceivedRows++;
                overreceivedUnits += diff;
            }

            if (d.getExpectedQuantity() != null && d.getExpectedQuantity() > 0) {
                expected += d.getExpectedQuantity();
            }
            if (d.getReceivedQuantity() != null && d.getReceivedQuantity() > 0) {
                received += d.getReceivedQuantity();
            }
            absDiffSum += Math.abs(diff);

            if (diff != 0) {
                asinMap.computeIfAbsent(orDefault(d.getAsin(), PORT_UNKNOWN),
                        k -> new AsinAccumulator(d.getAsin(), d.getSku())).add(d);
            }

            if (DISC_PENDING.equals(status) || DISC_INVESTIGATING.equals(status)) {
                ReceiptBoard.DiscrepancyItem item = new ReceiptBoard.DiscrepancyItem();
                item.setId(d.getId());
                item.setShipmentId(d.getShipmentId());
                item.setShipmentNo(shipmentNoIndex.get(d.getShipmentId()));
                item.setAsin(d.getAsin());
                item.setSku(d.getSku());
                item.setExpectedQuantity(d.getExpectedQuantity());
                item.setReceivedQuantity(d.getReceivedQuantity());
                item.setDifference(diff);
                item.setDiscrepancyType(type);
                item.setStatus(status);
                item.setCreateTime(d.getCreateTime());
                pendingItems.add(item);
            }
        }

        board.setPending(pending);
        board.setInvestigating(investigating);
        board.setResolved(resolved);
        board.setMatched(matched);
        board.setShortageRows(shortageRows);
        board.setOverreceivedRows(overreceivedRows);
        board.setTotalExpected(expected);
        board.setTotalReceived(received);
        board.setTotalDifference(received - expected);
        board.setShortageUnits(shortageUnits);
        board.setOverreceivedUnits(overreceivedUnits);
        board.setOpenShortageUnits(openShortageUnits);
        // 差异率按「绝对差异之和 / 应收合计」：直接算净差异会让少收与多收互相抵消，
        // 掩盖掉「一批少收 500 件、另一批多收 480 件」这种量级很大的问题
        board.setDiscrepancyRate(expected > 0
                ? BigDecimal.valueOf(absDiffSum).divide(BigDecimal.valueOf(expected), 4, RoundingMode.HALF_UP)
                : null);

        List<ReceiptBoard.AsinShortage> asins = new ArrayList<>(asinMap.size());
        asinMap.values().forEach(acc -> asins.add(acc.toVo()));
        asins.sort(Comparator.comparingLong(ReceiptBoard.AsinShortage::getShortageUnits).reversed());
        board.setTopShortageAsins(asins);

        pendingItems.sort(Comparator.comparingInt(
                (ReceiptBoard.DiscrepancyItem i) -> i.getDifference() == null ? 0 : Math.abs(i.getDifference())).reversed());
        board.setPendingItems(truncate(pendingItems, PENDING_DISCREPANCY_LIMIT, warnings, "待处理差异"));

        if (openShortageUnits > 0) {
            warnings.add("尚有 " + openShortageUnits + " 件少收未结案，可直接发起索赔或安排补发");
        }
        if (overreceivedUnits > 0 && shortageUnits > 0) {
            // 同时存在两个方向说明不是单纯的签收差错，可能是货件之间的数据串了
            warnings.add("同时存在少收与多收（少 " + shortageUnits + " 件 / 多 " + overreceivedUnits
                    + " 件），建议核对是否有个别货件的签收数量被记到了别的货件上");
        }
        if (matched > 0) {
            warnings.add(matched + " 条记录对账无差异，已单独计数，不计入差异统计");
        }
        board.setWarnings(warnings);

        log.debug("签收差异看板聚合完成：shopId={} 记录={} 待处理={} 未结案少收={} 件",
                shopId, rows.size(), pending + investigating, openShortageUnits);
        return board;
    }

    /** 单 ASIN 累积器 */
    private static class AsinAccumulator {

        private final String asin;
        private final String sku;
        private final Set<Long> shipments = new HashSet<>();

        private long expected;
        private long received;
        private long shortage;
        private long overreceived;

        AsinAccumulator(String asin, String sku) {
            this.asin = asin;
            this.sku = sku;
        }

        void add(FbaReceiptDiscrepancy d) {
            if (d.getShipmentId() != null) {
                shipments.add(d.getShipmentId());
            }
            if (d.getExpectedQuantity() != null && d.getExpectedQuantity() > 0) {
                expected += d.getExpectedQuantity();
            }
            if (d.getReceivedQuantity() != null && d.getReceivedQuantity() > 0) {
                received += d.getReceivedQuantity();
            }
            int diff = d.getDifference() == null ? 0 : d.getDifference();
            if (diff < 0) {
                shortage += -diff;
            } else if (diff > 0) {
                overreceived += diff;
            }
        }

        ReceiptBoard.AsinShortage toVo() {
            ReceiptBoard.AsinShortage vo = new ReceiptBoard.AsinShortage();
            vo.setAsin(asin);
            vo.setSku(sku);
            vo.setShipmentCount(shipments.size());
            vo.setExpected(expected);
            vo.setReceived(received);
            vo.setShortageUnits(shortage);
            vo.setOverreceivedUnits(overreceived);
            vo.setNetDifference(received - expected);
            return vo;
        }
    }

    // ================================================================ 工具

    /** 距今天数；创建时间缺失时返回 null（不是 0，否则会被当成「今天刚建」） */
    private Integer daysSince(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(time, LocalDateTime.now());
    }

    private int severityWeight(String severity) {
        if (SEVERITY_HIGH.equals(severity)) {
            return 0;
        }
        if (SEVERITY_MEDIUM.equals(severity)) {
            return 1;
        }
        return 2;
    }

    /**
     * 截断长列表并在 warnings 中说明。
     * <p>
     * 静默截断会让人以为「就这么多」，补一句提示才知道还有剩余需要到列表页看。
     */
    private <T> List<T> truncate(List<T> list, int limit, List<String> warnings, String what) {
        if (list.size() <= limit) {
            return list;
        }
        warnings.add(what + "较多，仅展示前 " + limit + " 条（共 " + list.size() + " 条）");
        return new ArrayList<>(list.subList(0, limit));
    }

    private String routeKey(String originPort, String destinationPort) {
        return orUnknown(originPort) + "→" + orUnknown(destinationPort);
    }

    private static String orUnknown(String value) {
        return isBlank(value) ? PORT_UNKNOWN : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
