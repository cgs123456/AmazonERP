package com.amz.agent;

import com.amz.result.Result;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;

/**
 * ERP Agent 工具的共享支撑函数（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>为什么单独成类</b>：这些是 30 个工具方法共用的纯函数——{@code fail} 有 90 处调用、
 * {@code toStr} 53 处、{@code isSuccess} 26 处。工具方法按依赖族拆到各自的类中之后，
 * 若各自复制一份 helper，就会出现「同一个 {@code toLong} 有两套空值口径」这类分叉；
 * 收在这里只留一份。
 * <p>
 * <b>刻意全部是 static</b>：它们不持有任何状态，也不需要 Spring 注入。工具类拿到的是
 * 构造好的实例方法，但底层转换逻辑是共享的——这样新增工具方法时不必决定「该继承还是该注入」。
 * <p>
 * <b>不是投机抽象</b>：这些函数在拆分前就已经存在且被大量调用，本类只是把它们从
 * ErpToolExecutor 里搬出来，没有新增任何参数、分支或配置项。
 */
public final class ErpToolSupport {

    /** 结果序列化器。与 ErpToolExecutor 原实现同一实例语义（无自定义配置）。 */
    private static final Gson GSON = new Gson();

    private ErpToolSupport() {
    }

    /** 成功结果：{@code {"ok":true,"message":...,"data":...}}。data 为 null 时按空串输出。 */
    public static String ok(String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("message", message);
        result.put("data", data == null ? "" : data);
        return GSON.toJson(result);
    }

    /** 失败结果：{@code {"ok":false,"message":...}}。 */
    public static String fail(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", false);
        result.put("message", message);
        return GSON.toJson(result);
    }

    /** Feign 调用返回的 Result 是否成功（200 且 data 非空）。 */
    public static boolean isSuccess(Result<?> result) {
        return result != null && result.getCode() == 200 && result.getData() != null;
    }

    /** 按 Marketplace ID 推断目标语言；未识别一律 en（与拆分前同口径）。 */
    public static String inferLanguage(String marketplaceId) {
        if (marketplaceId == null) return "en";
        return switch (marketplaceId) {
            case "A1PA6795UKMFR9" -> "de";  // DE
            case "A13V1IB3VIYZZH" -> "fr";  // FR
            case "APJ6JRA9NG5V4" -> "it";   // IT
            case "A1RKKUPIHCS9HS" -> "es";  // ES
            case "A1VC38T7YXB528" -> "ja";  // JP
            case "A1F83G8C2ARO7P" -> "en";  // UK
            default -> "en";
        };
    }

    public static Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.valueOf(obj.toString()); } catch (Exception e) { return null; }
    }

    public static Integer toInt(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.valueOf(obj.toString()); } catch (Exception e) { return defaultValue; }
    }

    public static double toDouble(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try { return Double.parseDouble(obj.toString()); } catch (Exception e) { return 0; }
    }

    public static Boolean toBool(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Boolean b) return b;
        if (obj instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(obj.toString());
    }

    public static String toStr(Object obj) {
        return obj == null ? null : obj.toString();
    }

    /** SSE 事件载荷截断，避免大 JSON 撑爆事件帧。 */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
