package com.amz.client.fallback;

import com.amz.client.ProcurementCostClient;
import com.amz.client.dto.RemoteInventoryBatch;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

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
        log.warn("Feign call to amz-service-procurement (cost) degraded: cause={}", cause.getMessage());
        return (shopId, sku) -> Result.failure("procurement service degraded: " + cause.getMessage());
    }
}
