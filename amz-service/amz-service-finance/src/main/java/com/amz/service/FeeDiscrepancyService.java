package com.amz.service;

import com.amz.dto.FeeDiscrepancyScanReport;
import com.amz.dto.InboundShortageRequest;
import com.amz.model.FeeDiscrepancy;

import java.util.List;

/**
 * 费用差异与短收候选服务（T06）。
 */
public interface FeeDiscrepancyService {

    /**
     * 扫描费用差异：以结算实际扣费对比费用预估，识别配送费多收、佣金多收、尺寸跳档。
     * <p>
     * 幂等：同 SKU 同类型已有未结案候选时跳过，不重复生成。
     *
     * @param marketplaceId 目标站点（可为 null，由 spapi 侧按店铺凭证解析）
     */
    FeeDiscrepancyScanReport scan(Long shopId, String marketplaceId);

    /**
     * 登记入库短收候选（第四类差异）。
     *
     * @return 新建候选 ID；同一货件同一 SKU 已登记过时返回 null
     */
    Long intakeInboundShortage(Long shopId, InboundShortageRequest request);

    /**
     * 查询候选（可按状态、类型过滤）。
     */
    List<FeeDiscrepancy> list(Long shopId, String status, String type);

    /**
     * 按 ID 查询（含租户归属校验）。
     */
    FeeDiscrepancy get(Long shopId, Long id);

    /**
     * 更新候选状态（含租户归属校验）。索赔链路（T07/T08）与人工排除共用。
     *
     * @return 候选存在且归属正确返回 true
     */
    boolean updateStatus(Long shopId, Long id, String status);
}
