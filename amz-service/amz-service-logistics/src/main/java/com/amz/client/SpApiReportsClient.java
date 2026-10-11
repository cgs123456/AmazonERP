package com.amz.client;

import com.amz.client.dto.RemoteReportInfo;
import com.amz.client.fallback.SpApiReportsClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * SP-API 报表三段式 Feign 客户端（logistics → spapi）。
 * <p>
 * <b>存在动因</b>：退货单此前只能手工登记（{@code source=AMAZON_FBA/FBM} 白名单留着
 * 但没人往里写）。Amazon 的 FBA 客户退货数据只能通过 Reports API 的
 * {@code GET_FBA_FULFILLMENT_CUSTOMER_RETURNS_DATA} 拉取——本客户端补上这条取数通道。
 * <p>
 * <b>为什么不复用 finance 的报表通道</b>：{@code /spapi/finance/report/*} 的白名单只放行
 * finance。让 logistics 借道会顺带获得财务报表取数权，违背最小权限。
 * spapi 侧另开 {@code /spapi/reports/*} 只放行 logistics。
 * <p>
 * <b>降级</b>：spapi 不可用时返回失败。退货同步是<b>可重入</b>的（幂等键拦重复），
 * 所以失败只要不推进游标即可下次重试；绝不能返回空报表当成功——那会让同步
 * 把「没拉到」记成「本期无退货」。
 */
@Component
@FeignClient(name = "amz-service-spapi", contextId = "spApiReportsClient",
        path = "/spapi/reports",
        fallbackFactory = SpApiReportsClientFallbackFactory.class)
public interface SpApiReportsClient {

    /**
     * 创建报表请求，返回 reportId。
     */
    @PostMapping("/request")
    Result<String> requestReport(@RequestParam("shopId") Long shopId,
                                 @RequestParam("marketplaceId") String marketplaceId,
                                 @RequestParam("reportType") String reportType,
                                 @RequestParam(value = "dataStartTime", required = false) String dataStartTime,
                                 @RequestParam(value = "dataEndTime", required = false) String dataEndTime);

    /**
     * 查询报表处理状态。
     */
    @GetMapping("/{reportId}")
    Result<RemoteReportInfo> getReport(@PathVariable("reportId") String reportId,
                                       @RequestParam("shopId") Long shopId);

    /**
     * 下载报表结果文档（自动解压）。
     */
    @GetMapping("/document/{documentId}")
    Result<String> downloadDocument(@PathVariable("documentId") String documentId,
                                    @RequestParam("shopId") Long shopId);
}
