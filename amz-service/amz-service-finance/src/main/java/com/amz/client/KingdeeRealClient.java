package com.amz.client;

import com.amz.model.AccountingVoucher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 金蝶云星空 API 真实客户端骨架。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 待实现项：
 * <ul>
 *   <li>POST /auth/login 获取 access_token</li>
 *   <li>POST /k3cloud/Voucher/Save 保存凭证</li>
 *   <li>POST /k3cloud/Voucher/Post 过账</li>
 * </ul>
 * 在真实 API 未对接前，{@link #syncVoucher(AccountingVoucher)} 不再抛出
 * {@link UnsupportedOperationException}（会导致上游标记 FAILED），
 * 改为打 warn 日志并返回 {@code KINGDEE_MOCK_} 前缀占位编号；
 * 上游 {@code FinanceServiceImpl.syncToKingdee} 据此将凭证状态标记为 SYNCING。
 */
@Slf4j
@Component
@Profile("!mock")
public class KingdeeRealClient implements KingdeeClient {

    @Value("${kingdee.api-gateway:https://api.kingdee.com}")
    private String apiGateway;

    @Value("${kingdee.app-id:}")
    private String appId;

    @Value("${kingdee.app-secret:}")
    private String appSecret;

    /**
     * 真实实现中可注入 RestTemplate / OkHttp；当前为骨架，未配置 Bean 时不影响编译。
     */
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String syncVoucher(AccountingVoucher voucher) {
        // 真实金蝶 API 待对接：打 warn 日志，返回 KINGDEE_MOCK_ 前缀占位编号，
        // 上游 FinanceServiceImpl.syncToKingdee 检测到该前缀会标记状态为 SYNCING 而非 FAILED，
        // 避免生产环境因未对接 API 而把所有凭证误标为同步失败。
        String mockNo = "KINGDEE_MOCK_" + System.currentTimeMillis();
        log.warn("真实金蝶 API 待对接，返回 mock 凭证号：voucherNo={} → kingdeeNo={}（apiGateway={}）",
                voucher.getVoucherNo(), mockNo, apiGateway);
        return mockNo;
    }
}