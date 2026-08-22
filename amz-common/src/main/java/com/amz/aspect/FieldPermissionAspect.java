package com.amz.aspect;

import com.amz.annotation.FieldPermission;
import com.amz.context.UserContext;
import com.amz.result.Result;
import com.amz.service.FieldPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字段级数据权限 AOP 切面。
 * <p>
 * 拦截所有 Controller 方法返回值：
 * <ul>
 *   <li>返回 {@code Result<T>} 时，对 T（若为实体或实体集合）做字段过滤；</li>
 *   <li>返回 {@code List<T>} / {@code Collection<T>} 时，对每个元素做字段过滤；</li>
 *   <li>其它返回类型跳过。</li>
 * </ul>
 * <p>
 * 过滤逻辑：扫描对象类中标注 {@link FieldPermission} 的字段，若该字段在
 * {@link FieldPermissionService#getHiddenFields} 返回的隐藏集合中，则通过反射置 null。
 * 同时把已过滤字段名收集到 {@code Result.hiddenFields}，前端据此显示 {@code ***}。
 * <p>
 * 安全降级：切面任何异常均被吞掉，业务返回原值；UserContext.role 缺失时不过滤。
 */
@Slf4j
@Aspect
@Component
public class FieldPermissionAspect {

    @Autowired
    private FieldPermissionService fieldPermissionService;

    /** 类 -> 该类上标注 @FieldPermission 的字段缓存，避免每次反射扫描。 */
    private static final ConcurrentHashMap<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    @Around("execution(* com.amz.controller..*.*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        try {
            String role = UserContext.getRole();
            if (role == null) {
                // 拦截器未注入角色（如白名单路径或未走 BaseAuthInterceptor 的服务），跳过过滤。
                return result;
            }
            if (result instanceof Result<?> r) {
                Object data = r.getData();
                Set<String> applied = filterObject(data, role);
                if (!applied.isEmpty()) {
                    // 合并已存在的 hiddenFields（Controller 多次包装的边界场景）
                    List<String> merged = new ArrayList<>(applied);
                    if (r.getHiddenFields() != null) {
                        Set<String> dedup = new HashSet<>(r.getHiddenFields());
                        dedup.addAll(applied);
                        merged = new ArrayList<>(dedup);
                    }
                    r.setHiddenFields(merged);
                }
            } else if (result instanceof Collection<?> col) {
                for (Object item : col) {
                    filterObject(item, role);
                }
            } else {
                filterObject(result, role);
            }
        } catch (Exception e) {
            // 切面异常绝不影响业务返回值
            log.warn("FieldPermissionAspect 过滤异常，跳过: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 过滤单个对象。若对象为集合则递归处理每个元素。
     *
     * @return 实际被置空的字段名集合
     */
    private Set<String> filterObject(Object target, String role) {
        Set<String> applied = new HashSet<>();
        if (target == null) {
            return applied;
        }
        if (target instanceof Collection<?> col) {
            for (Object item : col) {
                applied.addAll(filterObject(item, role));
            }
            return applied;
        }
        Class<?> clazz = target.getClass();
        // 跳过 JDK 类型 / String / Number / 基础包装类
        if (clazz.getName().startsWith("java.")) {
            return applied;
        }
        List<Field> annotatedFields = FIELD_CACHE.computeIfAbsent(clazz, this::collectAnnotatedFields);
        if (annotatedFields.isEmpty()) {
            return applied;
        }
        Set<String> hidden = fieldPermissionService.getHiddenFields(role, clazz.getSimpleName());
        if (hidden.isEmpty()) {
            return applied;
        }
        for (Field f : annotatedFields) {
            if (hidden.contains(f.getName())) {
                try {
                    f.setAccessible(true);
                    f.set(target, null);
                    applied.add(f.getName());
                } catch (IllegalAccessException ex) {
                    log.debug("字段 {} 不可访问，跳过置空: {}", f.getName(), ex.getMessage());
                } catch (Exception e) {
                    log.debug("字段 {} final/安全拦截，跳过置空: {}", f.getName(), e.getMessage());
                }
            }
        }
        return applied;
    }

    /**
     * 收集类（含父类）中所有标注 @FieldPermission 的字段。
     */
    private List<Field> collectAnnotatedFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(FieldPermission.class)) {
                    fields.add(f);
                }
            }
            c = c.getSuperclass();
        }
        return fields;
    }
}
