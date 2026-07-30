package com.amz.util;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Map 参数值类型转换工具类。
 * <p>
 * 统一收敛 ProductController / SelectionAnalysisController / ErpToolExecutor
 * 中重复的 toLong / toStr / toInt / toBigDecimal / toDouble 私有方法，
 * 便于后续逐步替换，避免散落多处的同义私有拷贝造成维护漂移。
 * <p>
 * 现有调用点暂不强制重构（避免大范围改动风险与测试破坏）：
 * 新增逻辑可优先使用本工具类，存量代码可在后续维护中渐进式迁移。
 * 若 ErpToolExecutor / ProductController 的对应私有方法自包含且测试依赖其行为，
 * 保留不动即可，待相关模块自然演进时再切换到本类。
 */
public final class MapArgUtils {

    private MapArgUtils() {
    }

    // ===== Long =====

    /**
     * 将任意值转为 Long。
     * <ul>
     *   <li>null 入参返回 null</li>
     *   <li>Number 直接取 longValue()</li>
     *   <li>其它对象调用 toString() 后尝试 Long.valueOf，失败返回 null</li>
     * </ul>
     */
    public static Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.valueOf(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 将 Map 中指定 key 的值转为 Long，key 不存在或转换失败返回 null。 */
    public static Long toLong(Map<String, Object> args, String key) {
        return toLong(args == null ? null : args.get(key));
    }

    /** 将任意值转为 Long，转换失败返回 defaultValue。 */
    public static Long toLong(Object obj, Long defaultValue) {
        Long v = toLong(obj);
        return v != null ? v : defaultValue;
    }

    /** 将 Map 中指定 key 的值转为 Long，转换失败返回 defaultValue。 */
    public static Long toLong(Map<String, Object> args, String key, Long defaultValue) {
        Long v = toLong(args == null ? null : args.get(key));
        return v != null ? v : defaultValue;
    }

    // ===== Integer =====

    /**
     * 将任意值转为 Integer。
     * <ul>
     *   <li>null 入参返回 null</li>
     *   <li>Number 直接取 intValue()</li>
     *   <li>其它对象调用 toString() 后尝试 Integer.valueOf，失败返回 null</li>
     * </ul>
     */
    public static Integer toInt(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.valueOf(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 将 Map 中指定 key 的值转为 Integer，key 不存在或转换失败返回 null。 */
    public static Integer toInt(Map<String, Object> args, String key) {
        return toInt(args == null ? null : args.get(key));
    }

    /** 将任意值转为 int，转换失败返回 defaultValue。 */
    public static int toInt(Object obj, int defaultValue) {
        Integer v = toInt(obj);
        return v != null ? v : defaultValue;
    }

    /** 将 Map 中指定 key 的值转为 int，转换失败返回 defaultValue。 */
    public static int toInt(Map<String, Object> args, String key, int defaultValue) {
        return toInt(args == null ? null : args.get(key), defaultValue);
    }

    // ===== Double =====

    /**
     * 将任意值转为 Double（包装类型）。
     * <ul>
     *   <li>null 入参返回 null</li>
     *   <li>Number 直接取 doubleValue()</li>
     *   <li>其它对象调用 toString() 后尝试 Double.valueOf，失败返回 null</li>
     * </ul>
     */
    public static Double toDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try {
            return Double.valueOf(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 将 Map 中指定 key 的值转为 Double，key 不存在或转换失败返回 null。 */
    public static Double toDouble(Map<String, Object> args, String key) {
        return toDouble(args == null ? null : args.get(key));
    }

    /** 将任意值转为 double，转换失败返回 defaultValue。 */
    public static double toDouble(Object obj, double defaultValue) {
        Double v = toDouble(obj);
        return v != null ? v : defaultValue;
    }

    /** 将 Map 中指定 key 的值转为 double，转换失败返回 defaultValue。 */
    public static double toDouble(Map<String, Object> args, String key, double defaultValue) {
        return toDouble(args == null ? null : args.get(key), defaultValue);
    }

    // ===== String =====

    /** 将任意值转为 String。null 入参返回 null，其它调用 toString()。 */
    public static String toStr(Object obj) {
        return obj == null ? null : obj.toString();
    }

    /** 将 Map 中指定 key 的值转为 String，key 不存在返回 null。 */
    public static String toStr(Map<String, Object> args, String key) {
        return toStr(args == null ? null : args.get(key));
    }

    /** 将任意值转为 String，null 时返回 defaultValue。 */
    public static String toStr(Object obj, String defaultValue) {
        return obj == null ? defaultValue : obj.toString();
    }

    /** 将 Map 中指定 key 的值转为 String，key 不存在返回 defaultValue。 */
    public static String toStr(Map<String, Object> args, String key, String defaultValue) {
        return toStr(args == null ? null : args.get(key), defaultValue);
    }

    // ===== BigDecimal =====

    /**
     * 将任意值转为 BigDecimal。
     * <ul>
     *   <li>null 入参返回 null</li>
     *   <li>已为 BigDecimal 直接返回</li>
     *   <li>其它对象调用 toString() 后尝试 new BigDecimal(...)，失败返回 null</li>
     * </ul>
     */
    public static BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        try {
            return new BigDecimal(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 将 Map 中指定 key 的值转为 BigDecimal，key 不存在或转换失败返回 null。 */
    public static BigDecimal toBigDecimal(Map<String, Object> args, String key) {
        return toBigDecimal(args == null ? null : args.get(key));
    }

    /** 将任意值转为 BigDecimal，转换失败返回 defaultValue。 */
    public static BigDecimal toBigDecimal(Object obj, BigDecimal defaultValue) {
        BigDecimal v = toBigDecimal(obj);
        return v != null ? v : defaultValue;
    }

    /** 将 Map 中指定 key 的值转为 BigDecimal，转换失败返回 defaultValue。 */
    public static BigDecimal toBigDecimal(Map<String, Object> args, String key, BigDecimal defaultValue) {
        BigDecimal v = toBigDecimal(args == null ? null : args.get(key));
        return v != null ? v : defaultValue;
    }
}