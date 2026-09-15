package com.amz.client;

import com.amz.client.dto.RemoteFeeEstimate;
import com.amz.client.dto.RemoteFinancialEvent;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.client.fallback.SpApiFinanceClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * amz-service-spapi 财务数据接口 Feign 客户端（finance 服务侧）。
 * <p>
 * 调用链：finance（业务层，落库与聚合）→ spapi（SP-API 取数层）。
 * 与 product 模块经 {@code SpapiFeedsClient} 调 feeds 的跨服务模式一致。
 * <p>
 * 服务不可用时由 {@link SpApiFinanceClientFallbackFactory} 返回失败而非伪造数据 ——
 * 财务域静默降级为空数据会让「利润为 0」看起来像真实结论，必须显式失败。
 */
@FeignClient(name = "amz-service-spapi", contextId = "spApiFinanceClient",
        fallbackFactory = SpApiFinanceClientFallbackFactory.class)
@RequestMapping("/spapi/finance")
public interface SpApiFinanceClient {

    /**
     * 创建报表请求，返回 reportId。
     */
    @PostMapping("/report/request")
    Result<String> requestReport(@RequestParam("shopId") Long shopId,
                                 @RequestParam("marketplaceId") String marketplaceId,
                                 @RequestParam("reportType") String reportType,
                                 @RequestParam(value = "dataStartTime", required = false) String dataStartTime,
                                 @RequestParam(value = "dataEndTime", required = false) String dataEndTime);

    /**
     * 查询报表处理状态（DONE 时携带 documentId）。
     */
    @GetMapping("/report/{reportId}")
    Result<RemoteReportInfo> getReport(@PathVariable("reportId") String reportId,
                                       @RequestParam("shopId") Long shopId);

    /**
     * 下载报表结果文档（spapi 侧按元数据自动解压，返回文本）。
     */
    @GetMapping("/document/{documentId}")
    Result<String> downloadDocument(@PathVariable("documentId") String documentId,
                                    @RequestParam("shopId") Long shopId);

    /**
     * 拉取结算事件（入账时间窗口，含分页）。
     */
    @GetMapping("/events")
    Result<List<RemoteFinancialEvent>> listEvents(@RequestParam("shopId") Long shopId,
                                                  @RequestParam(value = "postedAfter", required = false) String postedAfter,
                                                  @RequestParam(value = "postedBefore", required = false) String postedBefore);

    /**
     * FBA 费用预估（佣金 + 配送费 + 杂项）。
     *
     * @param idType 标识类型 ASIN / SKU；结算原表只带 SKU，费用比对传 SKU
     */
    @PostMapping("/fees/estimate")
    Result<RemoteFeeEstimate> estimateFees(@RequestParam("shopId") Long shopId,
                                           @RequestParam("marketplaceId") String marketplaceId,
                                           @RequestParam(value = "idType", required = false) String idType,
                                           @RequestParam("asin") String idValue,
                                           @RequestParam(value = "sku", required = false) String sku,
                                           @RequestParam("price") BigDecimal price,
                                           @RequestParam(value = "currency", required = false) String currency);
}
