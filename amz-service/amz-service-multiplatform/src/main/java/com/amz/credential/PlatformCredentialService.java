package com.amz.credential;

import com.amz.mapper.PlatformAccountMapper;
import com.amz.model.PlatformAccount;
import com.amz.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 多租户平台凭证解析服务。
 * <p>
 * 取代原先各客户端通过 {@code @Value("${platform.*}")} 注入的「全局单套凭证」，
 * 改为从 amz_platform_account 表按 (shopId, platform) 解析，使同一部署下多个店铺 /
 * 多套平台凭证可共存，消除「全局 @Value 不支持多租户」的缺陷（设计文档 H 项）。
 * <p>
 * 敏感字段（api_secret / access_token / refresh_token）在表中以密文存储，此处读取时
 * 经 {@link CryptoUtil} 解密；若未配置 crypto.key 或解密异常，原样回退，保证不阻断主链路。
 * <p>
 * 解析优先级：先查 (shopId, platform)；未命中则回退到该 platform 的首个账号
 * （兼容「无 shopId 上下文」的调用，如 markShipped 仅持平台订单号时）。
 * <p>
 * 软依赖：脱离 Spring 容器（如纯单测）时 credentialService 为 null，客户端以空凭证运行，
 * 触发既有的诚实失败逻辑，行为与原 @Value 空默认值完全一致。
 */
@Slf4j
@Component
public class PlatformCredentialService {

    @Autowired(required = false)
    private PlatformAccountMapper mapper;

    @Autowired(required = false)
    private CryptoUtil cryptoUtil;

    /** 各平台默认 API 端点（当账号未配置 apiEndpoint 时使用）。 */
    private static final Map<String, String> DEFAULT_BASE = Map.of(
            "TEMU", "https://open-api.temu.com",
            "TIKTOK", "https://open-api.tiktokglobalshop.com",
            "SHEIN", "https://open-api.shein.com"
    );

    /**
     * 解析指定店铺、指定平台的可用凭证。
     *
     * @param shopId   店铺 ID；为 null 时直接进入全局回退
     * @param platform 平台标识（TEMU / TIKTOK / SHEIN）
     * @return 凭证；未配置时返回 {@link PlatformCredential#empty()}
     */
    public PlatformCredential resolve(Long shopId, String platform) {
        if (mapper == null) {
            return PlatformCredential.empty();
        }
        try {
            if (shopId != null) {
                PlatformAccount acc = mapper.selectOne(new LambdaQueryWrapper<PlatformAccount>()
                        .eq(PlatformAccount::getShopId, shopId)
                        .eq(PlatformAccount::getPlatform, platform));
                if (acc != null) {
                    return toCredential(acc);
                }
            }
            // 全局回退：该平台的任意首个账号（兼容无 shopId 上下文的调用）
            PlatformAccount acc = mapper.selectOne(new LambdaQueryWrapper<PlatformAccount>()
                    .eq(PlatformAccount::getPlatform, platform)
                    .last("LIMIT 1"));
            if (acc != null) {
                return toCredential(acc);
            }
        } catch (Exception e) {
            log.warn("[PlatformCredentialService] 凭证解析失败 shopId={} platform={}：{}",
                    shopId, platform, e.getMessage());
        }
        return PlatformCredential.empty();
    }

    private PlatformCredential toCredential(PlatformAccount a) {
        String secret = decrypt(a.getApiSecretEncrypted());
        String token = decrypt(a.getAccessTokenEncrypted());
        String base = (a.getApiEndpoint() == null || a.getApiEndpoint().isBlank())
                ? DEFAULT_BASE.getOrDefault(a.getPlatform(), "")
                : a.getApiEndpoint();
        return new PlatformCredential(a.getApiKey(), secret, token, base);
    }

    /**
     * 解密：cryptoUtil 缺失或解密异常时原样回退，避免阻断主链路。
     */
    private String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        if (cryptoUtil != null) {
            try {
                return cryptoUtil.decrypt(ciphertext);
            } catch (Exception e) {
                log.debug("[PlatformCredentialService] 解密失败，回退原文：{}", e.getMessage());
                return ciphertext;
            }
        }
        return ciphertext;
    }
}
