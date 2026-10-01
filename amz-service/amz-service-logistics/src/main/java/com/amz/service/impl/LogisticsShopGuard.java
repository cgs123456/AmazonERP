package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import lombok.extern.slf4j.Slf4j;

/**
 * 物流域店铺越权校验（本模块唯一实现）。
 * <p>
 * 存在动因：此前 6 个 Service 各有一份私有 {@code requireShopAllowed}，其中 5 份只抛错、
 * 不留任何痕迹，只有 {@code LogisticsUpgradeServiceImpl} 那份会 log.warn。也就是说
 * 「有人试图越权访问别家店铺」这个安全事件在 6 个入口里有 5 个是查不到的；
 * 而任何一处口径调整都要同时改 6 份（改漏的那份就悄悄放宽了）。
 * <p>
 * 行为取自原来那 6 份的共同部分，日志取自唯一带日志的那份：抛错文案不变，
 * 越权一律留 warn。
 */
@Slf4j
final class LogisticsShopGuard {

    private LogisticsShopGuard() {
    }

    /**
     * @param shopId 目标店铺
     * @param what   业务对象名（拼进错误文案，保持各调用点原有提示不变）
     */
    static void requireShopAllowed(Long shopId, String what) {
        if (shopId == null) {
            throw new CodeErrorException(what + "缺少店铺 ID");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            // 与 ad 模块 AdTenantGuard 同口径：一次日志就能定位是谁、什么角色、
            // 授权了哪些店、碰了哪个店，不必再回去翻 JWT。
            log.warn("{}越权拦截：userId={} role={} 授权店铺={} 目标店铺={}",
                    what, UserContext.getUserId(), UserContext.getRole(),
                    UserContext.getShops(), shopId);
            throw new CodeErrorException(what + "不属于当前账号可操作的店铺");
        }
    }
}
