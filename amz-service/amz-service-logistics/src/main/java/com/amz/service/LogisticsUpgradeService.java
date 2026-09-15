package com.amz.service;

import com.amz.model.CarrierQuote;
import com.amz.model.FbaReceiptDiscrepancy;
import com.amz.model.FreightAllocation;
import com.amz.model.InventoryTransfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物流升级服务接口。
 * <p>
 * 覆盖：物流商比价 / 库存调拨 / 头程费用分摊 / 签收差异处理
 * <p>
 * <b>归属校验约定（与 {@code @ShopScoped} 切面互补）：</b>
 * 切面只能拿到 {@code @RequestParam / @PathVariable} 里的 shopId，
 * 因此凡是以「业务主键」而非 shopId 定位的单据操作（{@code xxx(id)} 形态），
 * 以及 shopId 来自请求体的写入，都必须由实现内部再做一次归属校验，
 * 否则同一份凭证改个数字就能操作他店单据。
 */
public interface LogisticsUpgradeService {

    // ===== 物流商比价 =====

    /** 保存物流商报价 */
    CarrierQuote saveQuote(CarrierQuote quote);

    /**
     * 查询**当前有效**的物流商报价。
     * <p>
     * 有效 = 状态为 ACTIVE 且未过失效日期。已过期的报价不返回——
     * 否则运价已失效但仍会被拿去做比价与选商，直接算错成本。
     *
     * @param serviceType 运输方式过滤，可空
     */
    List<CarrierQuote> listQuotes(Long shopId, String serviceType);

    /**
     * 运费比价（按路线查询所有承运商报价）。
     * <p>
     * 计费口径：重量价与体积价**取高**（承运商按两者中较高者计费），
     * 再套用最低收费与燃油附加费。只传其中一个参数时按可算的那个计。
     * <p>
     * 返回结构含 {@code currencies} 与 {@code recommendedByCurrency}：
     * 不同币种的报价不可直接比大小，因此按币种分别排序推荐，
     * 不做跨币种「全局最优」结论。
     */
    Map<String, Object> compareQuotes(Long shopId, String originPort, String destinationPort, BigDecimal weightKg, BigDecimal volumeCbm);

    /**
     * 把已过失效日期但状态仍为 ACTIVE 的报价批量置为 EXPIRED。
     *
     * @return 实际更新条数
     */
    int expireOutdatedQuotes(Long shopId);

    // ===== 库存调拨 =====

    /** 创建库存调拨单 */
    InventoryTransfer createTransfer(InventoryTransfer transfer);

    /**
     * 审批调拨单。
     * <p>
     * 仅 DRAFT / PENDING_APPROVAL 可被审批通过；已发出、已收货、已取消的单不可再改判。
     *
     * @param approved true 通过（→ APPROVED），false 驳回（→ CANCELLED）
     */
    InventoryTransfer approveTransfer(Long transferId, boolean approved);

    /** 确认调拨发出：仅 APPROVED 可发出（→ IN_TRANSIT） */
    InventoryTransfer shipTransfer(Long transferId, String carrier, String trackingNo);

    /** 确认调拨到货：仅 IN_TRANSIT 可收货（→ RECEIVED） */
    InventoryTransfer receiveTransfer(Long transferId);

    /** 查询调拨单列表 */
    List<InventoryTransfer> listTransfers(Long shopId, String status);

    // ===== 头程费用分摊 =====

    /** 批量保存头程费用分摊明细 */
    List<FreightAllocation> saveAllocations(List<FreightAllocation> allocations);

    /** 查询货件的头程费用分摊明细（会先校验货件归属） */
    List<FreightAllocation> listAllocations(Long shipmentId);

    /**
     * 按分摊方法计算头程费用。
     * <p>
     * 分摊后各行金额之和**等于**给定的费用总额：尾差由基准量最大的一行吸收，
     * 避免逐行四舍五入后总额对不上（财务口径上「分摊不等于总额」是硬伤）。
     *
     * @param method WEIGHT / VOLUME / QUANTITY，其他取值直接报错而不是静默按重量算
     */
    Map<String, Object> calculateFreightAllocation(Long shipmentId, String method, BigDecimal totalFreight, BigDecimal totalDuty, BigDecimal totalInsurance);

    // ===== FBA 签收差异 =====

    /**
     * 保存签收差异记录。
     * <p>
     * 差异数量由「实收 - 应收」计算得出，不接受调用方自报，避免差异与两个数量互相矛盾；
     * 差异为 0 时记为 {@code MATCHED}（对账无差异也是需要留痕的结论）。
     */
    FbaReceiptDiscrepancy saveDiscrepancy(FbaReceiptDiscrepancy discrepancy);

    /** 查询签收差异列表 */
    List<FbaReceiptDiscrepancy> listDiscrepancies(Long shopId, String status);

    /** 将差异标记为核查中（PENDING → INVESTIGATING） */
    FbaReceiptDiscrepancy startInvestigating(Long discrepancyId);

    /** 处理签收差异（PENDING / INVESTIGATING → RESOLVED） */
    FbaReceiptDiscrepancy resolveDiscrepancy(Long discrepancyId, String resolution);
}
