package com.amz.credential;

/**
 * 凭证 CAS 写入发生并发冲突。
 *
 * <p>该异常只表示另一写入已先成功；调用方可刷新版本后重试，绝不能静默覆盖。</p>
 */
public class ShopCredentialConcurrentUpdateException extends RuntimeException {

    public ShopCredentialConcurrentUpdateException(Long shopId) {
        super("店铺凭证已被其他请求修改，请刷新后重试: shopId=" + shopId);
    }
}
