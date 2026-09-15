package com.amz.service;

import com.amz.dto.SettlementIngestReport;
import com.amz.model.SettlementDetail;
import com.amz.parse.SettlementRow;

import java.util.List;

/**
 * 结算原表接入服务（T04）。
 */
public interface SettlementService {

    /**
     * 拉取并落库指定店铺的结算原表。
     * <p>
     * 完整链路：请求报表 → 轮询至 DONE → 下载文档 → 解析 TSV → 按业务指纹幂等落库。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId 目标 Marketplace ID
     * @param dataStartTime 报表数据起始时间（ISO 8601，可为 null）
     * @param dataEndTime   报表数据截止时间（ISO 8601，可为 null）
     * @return 导入报告（区分读入行数 / 入库行数 / 跳过重复 / 失败行）
     * @throws IllegalStateException 报表创建失败、处理失败（FATAL）或轮询超时
     */
    SettlementIngestReport sync(Long shopId, String marketplaceId,
                                String dataStartTime, String dataEndTime);

    /**
     * 幂等落库已解析的结算行（供同步链路与人工导入共用）。
     *
     * @param shopId 店铺 ID
     * @param rows   已解析行
     * @param report 报告对象（累加 inserted / skipped / failed / 金额 / 币种 / 警告）
     */
    void ingestParsedRows(Long shopId, List<SettlementRow> rows, SettlementIngestReport report);

    /**
     * 查询结算明细（可按订单号过滤）。
     */
    List<SettlementDetail> list(Long shopId, String orderId);
}
