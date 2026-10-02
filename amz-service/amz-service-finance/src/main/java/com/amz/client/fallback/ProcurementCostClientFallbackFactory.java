package com.amz.client.fallback;

import com.amz.client.ProcurementCostClient;
import com.amz.client.dto.RemoteBatchCostSummary;
import com.amz.client.dto.RemotePurchaseOrder;
import com.amz.result.Result;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;



/**
 * 采购批次成本降级工厂。
 * <p>
 * <b>刻意返回失败而非空列表</b>：成本侧拿不到数据时若返回空列表，
 * 单品利润会把采购成本当成 0，算出「利润率 80%」这种看起来很好的假结论。
 * 返回失败后由利润服务显式标记「成本数据缺失」，并拒绝把该 SKU 的利润当成确定值。
 */
@Slf4j
@Component
public class ProcurementCostClientFallbackFactory implements FallbackFactory<ProcurementCostClient> {

    @Override
    public ProcurementCostClient create(Throwable cause) {
        log.warn("Feign call to amz-service-procurement (cost) degraded", cause);
        final String reason = "procurement service degraded: " + cause.getMessage();
        return new ProcurementCostClient() {
            @Override
            public Result<RemoteBatchCostSummary> getCostSummary(Long shopId, String sku) {
                return Result.failure(reason);
            }

            @Override
            public Result<List<RemotePurchaseOrder>> listVoucherSources(Long shopId, Integer size, String cursor) {
                // 同 batch 成本一样：降级不返回空列表，财务据此知道采购成本不完整
                return Result.failure(reason);
            }
        };
    }
}
