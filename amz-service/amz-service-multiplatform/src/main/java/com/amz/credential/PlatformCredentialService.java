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
 * 经 {@link CryptoUtil} 解密。解密失败按「未配置」处理（返回 null），
 * <b>不</b>把密文原文当密钥用——那会让平台侧回 401，把「key 不匹配」伪装成「认证失败」。
 * <p>
 * 解析优先级（§S33.92 收紧）：
 * <ol>
 *   <li>shopId 非空：只查 (shopId, platform)，<b>查不到就返回空凭证</b>，绝不回退到
 *       其他店铺的账号——那等于让 A 店用 B 店的身份调平台 API；</li>
 *   <li>shopId 为 null（调用方确实没有店铺上下文）：回退到该 platform 按 id 升序首个账号。</li>
 * </ol>
 * <p>
 * 软依赖：脱离 Spring 容器（如纯单测）时 mapper / cryptoUtil 可能为 null，
 * 此时返回空凭证，客户端据此走既有的诚实失败逻辑。
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
     * <p><b>shopId 非空时绝不回退到其他店铺的凭证</b>（§S33.92）。
     * 旧实现在 (shopId, platform) 查不到时继续往下走全局回退，于是「店铺 A 没配 TEMU 账号」
     * 会让 A 的同步用<b>店铺 B 的凭证</b>去调平台 API：A 消耗 B 的配额，拉回来的商品与订单
     * 全挂到 A 名下。这与 §S33.91 修的跨店写库同源，只是错在凭证选择这一步。
     * 现在查不到就是查不到，返回 {@link PlatformCredential#empty()}，由调用方诚实失败
     * （Real 客户端会抛 {@code IllegalStateException} 点名「该店铺未配置凭证」），
     * 而不是悄悄换个身份继续。
     *
     * @param shopId   店铺 ID；null 表示调用方确实没有店铺上下文，此时才允许全局回退
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
                return acc == null ? PlatformCredential.empty() : toCredential(acc);
            }
            // 全局回退只服务「确实没有店铺上下文」的调用方。
            // 排序后取首个：LIMIT 1 不带 ORDER BY 时选中哪一行由存储引擎决定，
            // 同一份数据在不同执行计划下可能落到不同店铺（与 §S33.88 同类的「猜」）。
            PlatformAccount acc = mapper.selectOne(new LambdaQueryWrapper<PlatformAccount>()
                    .eq(PlatformAccount::getPlatform, platform)
                    .orderByAsc(PlatformAccount::getId)
                    .last("LIMIT 1"));
            return acc == null ? PlatformCredential.empty() : toCredential(acc);
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
     * 解密：cryptoUtil 缺失或解密失败一律返回 {@code null}（§S33.92）。
     *
     * <p>旧实现失败时<b>返回密文原文</b>，理由是「不阻断主链路」。但密文不能当密钥用：
     * 拿 base64 密文去算签名，平台侧只会回 401/签名错误，而日志里显示的是「认证失败」，
     * 排查方向会被带到平台配置上去，真正的「密钥不对 / 数据是旧 key 加密的」反而看不见。
     *
     * <p>返回 null 后由 Real 客户端的 {@code isBlank} 检查抛出点名的
     * {@code IllegalStateException}（「该店铺未配置可用凭证」），故障定位准确，
     * 且影响面仅限该店铺该平台，不会让服务整体不可用。
     *
     * <p>cryptoUtil 为 null 只可能出现在脱离 Spring 容器的纯单测里：
     * {@link CryptoUtil} 在 {@code @PostConstruct} 中未配置 {@code crypto.key} 会直接拒绝启动，
     * 所以生产环境不存在「没有 cryptoUtil 却能读到密文列」这个状态。
     */
    private String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        if (cryptoUtil == null) {
            return null;
        }
        try {
            return cryptoUtil.decrypt(ciphertext);
        } catch (Exception e) {
            // WARN 而非 DEBUG：解密失败意味着该账号的密文与当前 crypto.key 不匹配，
            // 是需要人介入的配置事故（换过 key、或导入了别处加密的数据），不是调试细节。
            log.warn("[PlatformCredentialService] 凭证解密失败，按未配置处理（crypto.key 与密文不匹配？）：{}",
                    e.getMessage());
            return null;
        }
    }
}
