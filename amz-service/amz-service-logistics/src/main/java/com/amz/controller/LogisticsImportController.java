package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.ImportReport;
import com.amz.dto.ImportShipmentDTO;
import com.amz.dto.ImportTrackingDTO;
import com.amz.result.Result;
import com.amz.service.LogisticsImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 物流数据外部导入端点（取数入口 A）。
 * <p>
 * <b>为何两个端点而非一个：</b>货件主单与轨迹点的<b>幂等键不同</b>（前者是货件编号，
 * 后者是「状态+时间」指纹），且货件必须先存在、轨迹才挂得上。合成一个端点会让
 * 「主单建了但轨迹全未匹配」这类半成品状态难以表达与重试；拆开后使用者的操作路径是
 * 「先导主单 → 再导轨迹」，两次都能单独重跑。
 * <p>
 * <b>店铺来源：</b>shopId 统一走查询参数，不取自请求体。这样 {@link ShopScoped}
 * 才能对入参做授权店铺校验；若允许请求体自带 shopId，等于把租户选择权交给调用方。
 */
@Slf4j
@RestController
@RequestMapping("/logistics/import")
public class LogisticsImportController {

    @Autowired
    private LogisticsImportService logisticsImportService;

    /**
     * 批量导入货件主单（按货件编号 upsert，可安全重复导入）。
     * <p>
     * POST /logistics/import/shipment?shopId=1
     * <pre>
     * [
     *   {"shipmentNo":"SHP20260818001","carrier":"COSCO","masterTrackingNo":"COSU1234567",
     *    "shippingMethod":"SEA","originPort":"Shenzhen","destinationPort":"LAX9",
     *    "boxCount":120,"weight":2400.00,"eta":"2026-09-20"}
     * ]
     * </pre>
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/shipment")
    public Result<ImportReport> importShipments(
            @RequestParam Long shopId,
            @RequestBody List<ImportShipmentDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return Result.failure("导入内容为空");
        }
        if (rows.size() > LogisticsImportService.MAX_SHIPMENT_ROWS) {
            return Result.failure("单次最多导入 " + LogisticsImportService.MAX_SHIPMENT_ROWS
                    + " 条货件，当前 " + rows.size() + " 条，请分批提交");
        }
        return Result.success(logisticsImportService.importShipments(rows, shopId));
    }

    /**
     * 批量导入运单轨迹点。
     * <p>
     * POST /logistics/import/tracking?shopId=1
     * <pre>
     * [
     *   {"shipmentNo":"SHP20260818001","trackingNo":"COSU1234567",
     *    "events":[
     *      {"status":"已开船","location":"Shenzhen","eventTime":"2026-08-05T09:00:00"},
     *      {"status":"清关中","location":"Los Angeles","eventTime":"2026-08-16T21:30:00"}
     *    ]}
     * ]
     * </pre>
     * 可重复提交全量历史轨迹：已存在的轨迹点会记入 {@code skippedEvents} 而非重复插入，
     * 因此调用方无需自行维护增量水位。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/tracking")
    public Result<ImportReport> importTracking(
            @RequestParam Long shopId,
            @RequestBody List<ImportTrackingDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return Result.failure("导入内容为空");
        }
        if (rows.size() > LogisticsImportService.MAX_TRACKING_ROWS) {
            return Result.failure("单次最多导入 " + LogisticsImportService.MAX_TRACKING_ROWS
                    + " 个运单，当前 " + rows.size() + " 个，请分批提交");
        }
        return Result.success(logisticsImportService.importTracking(rows, shopId));
    }
}
