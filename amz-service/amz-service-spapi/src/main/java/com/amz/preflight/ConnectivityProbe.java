package com.amz.preflight;

/**
 * 平台只读连通性探测抽象。
 *
 * <p><b>为什么抽这一层：</b>自检的价值在「分层定界」和「把状态码翻译成人话」，
 * 这两件事必须在<b>零网络</b>环境下被测到。把真实客户端藏在接口后面，
 * 测试即可用进程内桩替换，证据上限 E2（契约与分支），不越界宣称联调。
 *
 * <p><b>实现约束：</b>实现必须选择「权限最小、无 PII、不需要 RDT」的 operation，
 * 否则自检本身会引入额外的授权与合规要求（自检不该比业务更难通过）。
 */
@FunctionalInterface
public interface ConnectivityProbe {

    /**
     * 执行一次只读探测。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId 凭证登记的 marketplace
     * @return 探测摘要（如参与站点条数），<b>不得包含 PII 或凭证</b>
     * @throws RuntimeException 任何失败都抛出，由调用方统一分类；禁止返回 null 或空串假装成功
     */
    String probe(Long shopId, String marketplaceId);
}
