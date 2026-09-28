package com.amz.preflight;

import com.amz.client.SellersClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产用只读探测：{@code sellers.getMarketplaceParticipations}。
 *
 * <p><b>为什么选它（而不是 orders.getOrders）：</b>
 * <ul>
 *   <li>无 PII——不触碰买家姓名/地址，自检不需要申请 PII 权限，也不进 RDT 链路。</li>
 *   <li>权限最小——只读、无副作用，可重复调用；不会像 Feeds 那样产生写单据。</li>
 *   <li>定界清晰——它只验证「token + SigV4 + 端点 + 应用授权」，
 *       不混入订单查询参数、限流窗口或 RDT 等额外变量。</li>
 * </ul>
 * 现有 {@code ConnectorController#selfTest} 用的是 orders.getOrders：
 * 它更能串起业务链路，但失败时无法区分是授权问题还是查询参数/限流问题，
 * 因此两者是<b>互补</b>而非替代——本类负责「能不能连上」，selfTest 负责「业务链路通不通」。
 *
 * <p><b>mock profile 下不装配：</b>真实客户端在 mock 下不存在，
 * 此时自检会把 READ_API 记为 SKIP 并明确写出「进程未启用真实链路」，
 * 严禁把「mock 下没装配」包装成「平台连不上」或反过来。
 */
@Component
@Profile("!mock")
public class SellersConnectivityProbe implements ConnectivityProbe {

    private final SellersClient sellersClient;

    public SellersConnectivityProbe(SellersClient sellersClient) {
        this.sellersClient = sellersClient;
    }

    @Override
    public String probe(Long shopId, String marketplaceId) {
        JsonObject response = sellersClient.getMarketplaceParticipations(shopId, marketplaceId);
        JsonArray payload = response.getAsJsonArray("payload");
        return "marketplaceParticipations=" + payload.size();
    }
}
