package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 报表写入端点的店铺归属守卫。
 * <p>
 * 动因（2026-10-03 功能覆盖清点 N1）：5 个摄取端点（{@code /report/v2/profit}、
 * {@code /inventory-turnover}、{@code /sales-daily}、{@code /business-overview}、
 * {@code /report/profit/allocation}）都标了 {@code @ShopScoped}，但
 * {@code ShopIdGuardAspect} 只从 {@code @PathVariable}/{@code @RequestParam} 里取名为
 * shopId 的 Long 参数——这些端点的 shopId 在 {@code @RequestBody} 的实体里，切面解析不到就
 * 直接放行，等于挂着守卫注解却不做任何归属校验；service 层此前也没有兜底。
 * <p>
 * 这里用严格档 {@link UserContext#isShopAllowedStrict(Long)} 而不是宽松的
 * {@link UserContext#isShopAllowed(Long)}：宽松档在「没有店铺列表」时放行以兼容定时任务，
 * 而这 5 个端点今天没有任何内部调用方（既没有 Feign 客户端调用，也没有进程内调用，
 * 前端也刻意不接），所以没有需要放行的合法来源。将来若真要让调度器或别的服务写入，
 * 应该把端点显式声明成双信任（{@code @InternalServiceAccess} +
 * {@link UserContext#isShopAllowedByUserOrTrustedService(Long)}），而不是回头放宽这里。
 */
@Slf4j
@Component
public class ReportTenantGuard {

    /**
     * 校验当前调用方有权写入指定店铺的数据。
     *
     * @param shopId 请求体实体自带的店铺 id
     * @param what   写入对象名，只用于日志与错误信息
     */
    public void requireShopAccess(Long shopId, String what) {
        if (shopId == null) {
            throw new AttrIsNullException(what + "的店铺ID不能为空");
        }
        if (!UserContext.isShopAllowedStrict(shopId)) {
            log.warn("报表写入越权拦截：what={}, userId={}, role={}, shopId={}, authorizedShops={}",
                    what, UserContext.getUserId(), UserContext.getRole(), shopId, UserContext.getShops());
            throw new CodeErrorException("店铺不存在或无权写入：" + shopId);
        }
    }
}
