package com.amz.client;

import com.amz.model.AccountingVoucher;

/**
 * 金蝶云星空 API 客户端接口。
 * <p>
 * 生产实现使用 Web API：
 * <ol>
 *   <li>POST AuthService.LoginByAppSecret 获取并缓存会话 Cookie</li>
 *   <li>POST DynamicFormService.Save 保存 GL_VOUCHER</li>
 * </ol>
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link KingdeeMockClient} 离线模拟，仅用于本地开发</li>
 *   <li>{@code !mock}：{@link KingdeeRealClient} 真实 API，配置缺失或调用失败时显式抛错</li>
 * </ul>
 */
public interface KingdeeClient {

    /**
     * 当前实现是否为离线模拟。
     * <p>
     * 模拟实现返回 true，服务层据此写入明确的 {@code MOCK} 状态，绝不冒充
     * {@code SYNCED}。真实实现使用默认 false。
     */
    default boolean isMock() {
        return false;
    }

    /**
     * 同步凭证到金蝶。
     *
     * @return 真实实现返回金蝶凭证号；失败必须抛异常，禁止返回占位号
     */
    String syncVoucher(AccountingVoucher voucher);
}
