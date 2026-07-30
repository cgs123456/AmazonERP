package com.amz.client;

import com.amz.model.AccountingVoucher;

/**
 * 金蝶云星空 API 客户端接口。
 * <p>
 * 生产环境对接路径（金蝶云星空开放平台）：
 * <ol>
 *   <li>POST /auth/login - 获取 access_token</li>
 *   <li>POST /k3cloud/Voucher/Save - 保存凭证</li>
 *   <li>POST /k3cloud/Voucher/Post - 过账</li>
 * </ol>
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link KingdeeMockClient} 离线模拟</li>
 *   <li>{@code !mock}：{@link KingdeeRealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface KingdeeClient {

    /**
     * 同步凭证到金蝶。
     *
     * @return 金蝶凭证号
     */
    String syncVoucher(AccountingVoucher voucher);
}
