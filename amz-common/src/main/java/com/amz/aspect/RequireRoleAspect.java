package com.amz.aspect;

import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 角色权限校验切面（RBAC 写操作防护）。
 * <p>
 * 拦截所有标注 {@link RequireRole} 的 Controller 方法，
 * 校验「本次操作面向的店铺」上的角色是否在允许集合内：
 * <ul>
 *   <li>命中 → 放行执行原方法；</li>
 *   <li>未命中 / 角色缺失 → 返回 {@link Result#failure(String)}，方法不执行；</li>
 *   <li>已由 {@code BaseAuthInterceptor} 校验并注入的白名单服务身份 → 放行，
 *       由端点 {@code @InternalServiceAccess} 承担服务级授权；</li>
 *   <li>切面内部异常按拒绝处理（fail-closed：权限组件故障不应放行写操作）。</li>
 * </ul>
 *
 * <h2>判定用哪个角色：店铺级优先，全局兜底</h2>
 * 角色由 {@link UserContext#resolveRole(Long)} 解析，店铺 id 取自**方法参数**
 * （{@link ShopIdParameterResolver}，与 {@code ShopIdGuardAspect} 同一套解析）。
 * <p>
 * <b>为什么必须用方法参数而不是请求头 shopId</b>：两者可以不一致——客户端把
 * header 设成店 A（自己有 OPERATOR），请求却作用于店 B。只看 header 就会出现
 * 「拿店 A 的角色去写店 B」的错配。方法参数才是这次操作真正要动的店。
 * <p>
 * <b>为什么不能只看店铺角色</b>：方法签名里没有 shopId 的写端点相当多
 * （入库/出库单 create/cancel/pick/pack/ship、{@code /{id}/role}、跨店同步等），
 * 它们没有「面向哪个店」这一个可解析的答案。对这些端点用全局角色是唯一正确的降级：
 * 强行要求店铺上下文，等于让一批本来正常的端点在全量接入店铺级角色之前失效。
 * <p>
 * 因此本切面的口径是：**能定位到店铺就按店铺判（精细），定位不到按全局判（兜底）**，
 * 两者都是后端签发的权威角色，没有前端可影响的输入。
 */
@Slf4j
@Aspect
@Component
public class RequireRoleAspect {

    @Around("@annotation(requireRole)")
    public Object check(ProceedingJoinPoint pjp, RequireRole requireRole) throws Throwable {
        Set<String> allowed = new HashSet<>(Arrays.asList(requireRole.value()));
        String serviceName = null;
        try {
            serviceName = UserContext.getInternalService();
        } catch (Exception e) {
            log.warn("RequireRole 读取服务身份失败，继续按用户角色拒绝：{}", e.getMessage());
        }
        if (serviceName != null) {
            log.info("RequireRole 放行受信服务调用：method={}, service={}",
                    pjp.getSignature().toShortString(), serviceName);
            return pjp.proceed();
        }

        Long shopId = null;
        String role = null;
        try {
            shopId = resolveShopId(pjp);
            role = UserContext.resolveRole(shopId);
        } catch (Exception e) {
            log.warn("RequireRole 读取角色上下文失败，按拒绝处理：{}", e.getMessage());
        }
        if (role == null || !allowed.contains(role)) {
            log.warn("RequireRole 拦截：method={} shopId={} required={} actual={}",
                    pjp.getSignature().toShortString(), shopId,
                    Arrays.toString(requireRole.value()), role);
            return Result.failure("无权限执行该操作（需要角色：" + String.join("/", requireRole.value()) + "）");
        }
        return pjp.proceed();
    }

    /**
     * 解析本次调用面向的店铺；方法签名里没有 shopId 参数时返回 null（走全局角色）。
     */
    private static Long resolveShopId(ProceedingJoinPoint pjp) {
        try {
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            return ShopIdParameterResolver.resolve(method, pjp.getArgs());
        } catch (Exception e) {
            // 解析失败不等于「没有店铺」——但也不能因此拒绝：本切面是授权链的一环，
            // 拿不到店铺时回落全局角色是既有的保守基线（改动前一直是全局角色）。
            // 拒绝会让签名变动这类无害改动直接打成 403，代价大于收益。
            log.warn("RequireRole 解析 shopId 失败，本次按全局角色判定：{}", e.getMessage());
            return null;
        }
    }
}
