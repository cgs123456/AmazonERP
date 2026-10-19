package com.amz.service.impl;

import com.amz.client.SpApiFbaInboundClient;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FBA 入库计划后续步骤：读取选项 + 回写跟踪号。
 * <p>
 * <b>为什么单开一个服务而不是塞进 {@link FbaInboundPlanService}</b>：
 * 那个服务只负责「创建计划」这一个写动作，且它的测试钉死了官方请求形状。
 * 后续步骤是另一类职责——读平台状态、把外部标识回写到本地货件——混进去会让
 * 那个 181 行的服务变成 400 行的大杂烩，且「创建」的契约测试会被无关改动干扰。
 * <p>
 * <b>刻意不做（附理由，不是遗漏）</b>：装箱（{@code setPackingInformation} /
 * {@code generatePackingOptions} / {@code confirmPackingOption}）、分仓确认
 * （{@code confirmPlacementOption}）、运输（{@code generateTransportationOptions} /
 * {@code confirmTransportationOptions}）都<b>不</b>自动执行。原因：
 * <ul>
 *   <li>装箱要箱规、箱内 SKU 明细、重量体积——ERP 的 {@code amz_fba_shipment}
 *       只有汇总级的 {@code box_count}，没有逐箱数据（{@code amz_fba_shipment_item}
 *       是 SKU 级而非箱级）。拿汇总值凑箱级请求等于编造，Amazon 会按错误箱规计费。</li>
 *   <li>分仓选项决定货发到哪几个仓、产生多少 placement fee，这是<b>要花钱的运营决策</b>；
 *       自动确认一个选项就是替运营签字。</li>
 *   <li>运输选项绑定承运商、运费与上门时间；选错会产生真实运费。</li>
 * </ul>
 * 所以本服务只把<b>平台给出的选项原样读出来</b>交给运营判断，写动作必须由人显式发起
 * （跟踪号回写是例外：它是把本地已有的运单号同步给 Amazon，不产生费用）。
 * <p>
 * <b>异步语义</b>：{@code createInboundPlan} 等操作返回 202 + {@code operationId}，
 * 真正结果要轮询 {@code getInboundOperationStatus}。本服务提供
 * {@link #getOperationStatus} 供上层查询，但<b>不在同步请求里阻塞轮询</b>——
 * 入库计划创建可能耗时数秒到数十秒，阻塞会让 HTTP 请求超时，而超时后运营会重试，
 * 于是一次计划变成两个。
 */
@Slf4j
@Service
public class FbaInboundPlanQueryService {

    static final String OP_GET_INBOUND_PLAN = "fbaInbound.getInboundPlan";
    static final String OP_LIST_PACKING_OPTIONS = "fbaInbound.listPackingOptions";
    static final String OP_LIST_PLACEMENT_OPTIONS = "fbaInbound.listPlacementOptions";
    static final String OP_GET_SHIPMENT = "fbaInbound.getShipment";
    static final String OP_LIST_SHIPMENT_ITEMS = "fbaInbound.listShipmentItems";
    static final String OP_UPDATE_TRACKING = "fbaInbound.updateShipmentTrackingDetails";
    static final String OP_GET_OPERATION_STATUS = "fbaInbound.getInboundOperationStatus";

    @Autowired(required = false)
    private SpApiFbaInboundClient spApiFbaInboundClient;

    /**
     * 读取入库计划详情（含状态、装箱/分仓选项摘要、货件列表）。
     *
     * @param shopId        店铺
     * @param marketplaceId 站点
     * @param inboundPlanId Amazon 返回的计划 ID
     * @return 官方响应原文（含 status / packingOptions / placementOptions / shipments）
     */
    public Map<String, Object> getInboundPlan(Long shopId, String marketplaceId, String inboundPlanId) {
        requireScope(shopId, marketplaceId);
        requireText(inboundPlanId, "inboundPlanId");
        return execute(OP_GET_INBOUND_PLAN, shopId, marketplaceId,
                Map.of("inboundPlanId", inboundPlanId.trim()), null, null);
    }

    /**
     * 列出装箱选项。运营据此决定用哪个装箱方案。
     * <p>
     * 注意：本方法只读。<b>确认</b>装箱选项会绑定箱规并影响后续分仓，
     * 必须由运营在卖家后台或显式调用通用的 {@code /spapi/operations} 端点完成。
     */
    public Map<String, Object> listPackingOptions(Long shopId, String marketplaceId, String inboundPlanId) {
        requireScope(shopId, marketplaceId);
        requireText(inboundPlanId, "inboundPlanId");
        return execute(OP_LIST_PACKING_OPTIONS, shopId, marketplaceId,
                Map.of("inboundPlanId", inboundPlanId.trim()), null, null);
    }

    /**
     * 列出分仓选项（每个选项含费用、折扣、目的货件列表）。
     * <p>
     * 只读：确认分仓选项会产生 placement fee 并拆出多个货件，属于付费决策。
     */
    public Map<String, Object> listPlacementOptions(Long shopId, String marketplaceId, String inboundPlanId) {
        requireScope(shopId, marketplaceId);
        requireText(inboundPlanId, "inboundPlanId");
        return execute(OP_LIST_PLACEMENT_OPTIONS, shopId, marketplaceId,
                Map.of("inboundPlanId", inboundPlanId.trim()), null, null);
    }

    /**
     * 读取单个货件详情（含 {@code shipmentConfirmationId}，即卖家后台看到的 FBA 货件号）。
     *
     * @param shipmentId Amazon 侧的 shipmentId（38 位 UUID 形状，<b>不是</b>本地货件主键）
     */
    public Map<String, Object> getShipment(Long shopId, String marketplaceId,
                                           String inboundPlanId, String shipmentId) {
        requireScope(shopId, marketplaceId);
        requireText(inboundPlanId, "inboundPlanId");
        requireText(shipmentId, "shipmentId");
        return execute(OP_GET_SHIPMENT, shopId, marketplaceId,
                Map.of("inboundPlanId", inboundPlanId.trim(), "shipmentId", shipmentId.trim()),
                null, null);
    }

    /**
     * 列出货件内明细（SKU / 数量 / prep 指令）。用于把本地货件明细与 Amazon 侧核对。
     */
    public Map<String, Object> listShipmentItems(Long shopId, String marketplaceId,
                                                 String inboundPlanId, String shipmentId) {
        requireScope(shopId, marketplaceId);
        requireText(inboundPlanId, "inboundPlanId");
        requireText(shipmentId, "shipmentId");
        return execute(OP_LIST_SHIPMENT_ITEMS, shopId, marketplaceId,
                Map.of("inboundPlanId", inboundPlanId.trim(), "shipmentId", shipmentId.trim()),
                null, null);
    }

    /**
     * 把本地运单号回写给 Amazon。
     * <p>
     * <b>为什么这一步值得自动化的例外</b>：本地已经有 {@code master_tracking_no}
     * （货件确认发货时录入），回写给 Amazon 只是同步事实，不产生费用、不改变货发往哪里。
     * 不回写的后果很具体：Amazon 侧看不到跟踪号，货件会被判为迟发，影响绩效指标。
     * <p>
     * <b>为什么只支持 SPD（小包裹）</b>：官方 {@code TrackingDetails} 有两种形状，
     * {@code spdTrackingDetail} 需要<b>逐箱</b>的 {@code boxId + trackingId}；
     * {@code ltlTrackingDetail} 是零担。本地货件只有主运单号、没有箱级数据，
     * 因此只接受「单箱/同单号」的 SPD 场景：调用方必须显式给出 boxId。
     * 拿一个主运单号假装覆盖所有箱，Amazon 会按缺失跟踪号处理，比不做更糟。
     *
     * @param boxTracking 逐箱跟踪号（boxId → trackingId）；至少一项
     */
    public Map<String, Object> updateTracking(Long shopId, String marketplaceId,
                                              String inboundPlanId, String shipmentId,
                                              Map<String, String> boxTracking) {
        requireScope(shopId, marketplaceId);
        requireText(inboundPlanId, "inboundPlanId");
        requireText(shipmentId, "shipmentId");
        if (boxTracking == null || boxTracking.isEmpty()) {
            throw new CodeErrorException(
                    "逐箱跟踪号不能为空：Amazon 的 SPD 跟踪号是箱级的（boxId → trackingId），"
                            + "不接受单个主运单号代替");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : boxTracking.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                throw new CodeErrorException("boxId 不能为空");
            }
            if (!StringUtils.hasText(entry.getValue())) {
                throw new CodeErrorException("跟踪号不能为空：boxId=" + entry.getKey());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("boxId", entry.getKey().trim());
            item.put("trackingId", entry.getValue().trim());
            items.add(item);
        }
        Map<String, Object> body = Map.of(
                "trackingDetails", Map.of("spdTrackingDetail", Map.of("spdTrackingItems", items)));
        return execute(OP_UPDATE_TRACKING, shopId, marketplaceId,
                Map.of("inboundPlanId", inboundPlanId.trim(), "shipmentId", shipmentId.trim()),
                null, body);
    }

    /**
     * 查询异步操作状态（{@code createInboundPlan} / {@code generatePackingOptions} /
     * {@code confirmPlacementOption} 等返回的 {@code operationId}）。
     *
     * @return 官方响应：{@code operationStatus} 为 SUCCESS / FAILED / IN_PROGRESS，
     *         失败时 {@code operationProblems} 给出原因
     */
    public Map<String, Object> getOperationStatus(Long shopId, String marketplaceId, String operationId) {
        requireScope(shopId, marketplaceId);
        requireText(operationId, "operationId");
        return execute(OP_GET_OPERATION_STATUS, shopId, marketplaceId,
                Map.of("operationId", operationId.trim()), null, null);
    }

    private Map<String, Object> execute(String operationId, Long shopId, String marketplaceId,
                                        Map<String, String> pathParameters,
                                        Map<String, String> queryParameters,
                                        Map<String, Object> body) {
        if (spApiFbaInboundClient == null) {
            throw new CodeErrorException("FBA 入库计划通道不可用（spapi 客户端未装配）");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shopId", shopId);
        request.put("marketplaceId", marketplaceId);
        request.put("pathParameters", pathParameters);
        if (queryParameters != null && !queryParameters.isEmpty()) {
            request.put("queryParameters", queryParameters);
        }
        if (body != null) {
            request.put("body", body);
        }
        log.info("FBA 入库计划操作 operation={} shopId={} planId={} operator={}",
                operationId, shopId, pathParameters.get("inboundPlanId"), UserContext.getUserId());

        Result<Map<String, Object>> result = spApiFbaInboundClient.execute(operationId, request);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            String reason = result == null ? "无响应" : result.getMessage();
            // 读取失败不能返回空 Map：上层会把空 Map 当成「计划没有装箱选项」，
            // 运营据此以为 Amazon 还没生成，实际是调用失败。
            throw new CodeErrorException("查询 FBA 入库计划失败（" + operationId + "）：" + reason);
        }
        return result.getData();
    }

    private void requireScope(Long shopId, String marketplaceId) {
        if (shopId == null) {
            throw new CodeErrorException("shopId 不能为空");
        }
        if (!StringUtils.hasText(marketplaceId)) {
            throw new CodeErrorException("目的站点（marketplaceId）不能为空");
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new CodeErrorException(field + " 不能为空");
        }
    }
}
