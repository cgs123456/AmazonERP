package com.amz.client;

import com.amz.model.AccountingVoucher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 金蝶云星空 API 模拟客户端。
 * <p>
 * 生产环境对接路径（金蝶云星空开放平台）：
 * <ol>
 *   <li>POST /auth/login - 获取 access_token</li>
 *   <li>POST /k3cloud/Voucher/Save - 保存凭证</li>
 *   <li>POST /k3cloud/Voucher/Post - 过账</li>
 * </ol>
 * 当前为离线模拟实现。
 */
@Slf4j
@Component
public class KingdeeClient {

    /**
     * 同步凭证到金蝶。
     *
     * @return 金蝶凭证号
     */
    public String syncVoucher(AccountingVoucher voucher) {
        String kingdeeNo = "KD-" + System.currentTimeMillis();
        log.info("金蝶凭证同步模拟：voucherNo={} → kingdeeNo={}", voucher.getVoucherNo(), kingdeeNo);
        return kingdeeNo;
    }
}
