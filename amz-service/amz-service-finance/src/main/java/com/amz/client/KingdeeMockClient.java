package com.amz.client;

import com.amz.model.AccountingVoucher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 金蝶云星空 API 模拟客户端。
 * <p>
 * 离线模拟实现。仅在 {@code spring.profiles.active=mock} 时生效。
 */
@Slf4j
@Component
@Profile("mock")
public class KingdeeMockClient implements KingdeeClient {

    @Override
    public String syncVoucher(AccountingVoucher voucher) {
        String kingdeeNo = "KD-" + System.currentTimeMillis();
        log.info("金蝶凭证同步模拟：voucherNo={} → kingdeeNo={}", voucher.getVoucherNo(), kingdeeNo);
        return kingdeeNo;
    }
}
