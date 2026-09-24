package com.amz.auth;

import com.amz.config.SpApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 官方必填请求头 {@code user-agent} 的拼装器（P0-35）。
 * <p>
 * 一手依据（官方 connecting-to-the-selling-partner-api 页，2026-09-24 抓取）：
 * <ul>
 *   <li>逐字要求："You must include a user-agent header in every request to the SP-API."</li>
 *   <li>拼装顺序：应用名 + {@code /} + 版本 + 空格 + {@code (} + 属性对 + {@code )}，
 *       官方示例 {@code MySellingTool/2.0 (Language=Java/1.8.0.221; Platform=Windows/10)}</li>
 *   <li>转义（反斜杠）：反斜杠本身；应用名中的 {@code /}；版本中的 {@code (}；
 *       属性名中的 {@code =}；属性值中的 {@code )} 与 {@code ;}</li>
 *   <li>长度上限 500 字符；最小信息必须含应用名、版本、语言</li>
 * </ul>
 * <p>
 * <b>失败策略：</b>静态 {@link #compose} 是契约原语，输入非法即 fail-closed
 * （空白抛 {@link IllegalArgumentException}、超长抛 {@link IllegalStateException}，禁止静默截断）；
 * 面向 Spring 的 {@link #value()} 只做「空配置回退默认值」，因为配置缺省不应让整条出站链路不可用。
 */
@Component
public class SpApiUserAgent {

    /** 官方上限：500 字符。 */
    public static final int MAX_LENGTH = 500;

    private static final String DEFAULT_APP_NAME = "AmazonERP";
    private static final String DEFAULT_APP_VERSION = "1.0-SNAPSHOT";

    private static final Logger log = LoggerFactory.getLogger(SpApiUserAgent.class);

    private final SpApiConfig config;
    private final AtomicBoolean blankConfigWarned = new AtomicBoolean();

    public SpApiUserAgent(SpApiConfig config) {
        this.config = config;
    }

    /**
     * 解析最终 user-agent：显式 {@code spapi.user-agent} 覆盖优先，否则按字段拼装。
     */
    public String value() {
        String override = config.getUserAgent();
        if (override != null && !override.isBlank()) {
            return override;
        }
        String appName = config.getAppName();
        String appVersion = config.getAppVersion();
        String language = config.getLanguage();
        if (isBlank(appName) || isBlank(appVersion) || isBlank(language)) {
            if (blankConfigWarned.compareAndSet(false, true)) {
                log.warn(
                        "spapi 应用标识配置不完整（appName/appVersion/language 存在空白），已回退默认值；"
                                + "建议显式配置 spapi.app-name / spapi.app-version / spapi.language 或 spapi.user-agent");
            }
        }
        return compose(isBlank(appName) ? DEFAULT_APP_NAME : appName,
                isBlank(appVersion) ? DEFAULT_APP_VERSION : appVersion,
                isBlank(language) ? defaultLanguage() : language,
                config.getPlatform());
    }

    /** 默认语言属性：{@code Java/<JVM 版本>}。 */
    public static String defaultLanguage() {
        return "Java/" + Runtime.version();
    }

    /**
     * 按官方规则拼装 user-agent（含 Platform 可选属性）。
     *
     * @throws IllegalArgumentException 应用名/版本/语言为空
     * @throws IllegalStateException    结果超过 {@link #MAX_LENGTH}
     */
    public static String compose(String appName, String version, String language, String platform) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Language", language);
        if (!isBlank(platform)) {
            attributes.put("Platform", platform);
        }
        return compose(appName, version, attributes);
    }

    /**
     * 按官方规则拼装 user-agent（属性对由调用方给定，顺序即输出顺序）。
     *
     * @throws IllegalArgumentException 应用名/版本为空，或属性名为空（属性值见下）
     * @throws IllegalStateException    结果超过 {@link #MAX_LENGTH}（禁止静默截断）
     */
    public static String compose(String appName, String version, Map<String, String> attributes) {
        requireNonBlank(appName, "应用名（spapi.app-name / SPAPI_APP_NAME）");
        requireNonBlank(version, "版本（spapi.app-version / SPAPI_APP_VERSION）");
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("user-agent 至少需要 Language 属性");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(escapeAppName(appName)).append('/').append(escapeVersion(version)).append(" (");
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            requireNonBlank(entry.getKey(), "属性名");
            String attributeValue = entry.getValue();
            if (attributeValue == null || attributeValue.isBlank()) {
                throw new IllegalArgumentException("user-agent 属性值不得为空：" + entry.getKey());
            }
            if (!first) {
                sb.append("; ");
            }
            sb.append(escapeAttributeName(entry.getKey()))
                    .append('=')
                    .append(escapeAttributeValue(attributeValue));
            first = false;
        }
        sb.append(')');

        String userAgent = sb.toString();
        if (userAgent.length() > MAX_LENGTH) {
            throw new IllegalStateException("user-agent 长度 " + userAgent.length()
                    + " 超过官方上限 " + MAX_LENGTH + " 字符（禁止静默截断）；"
                    + "请缩短 spapi.app-name / spapi.app-version 或属性值");
        }
        return userAgent;
    }

    private static void requireNonBlank(String value, String what) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("user-agent 的" + what + "不得为空");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String escapeAppName(String value) {
        return escapeBackslash(value).replace("/", "\\/");
    }

    private static String escapeVersion(String value) {
        return escapeBackslash(value).replace("(", "\\(");
    }

    private static String escapeAttributeName(String value) {
        return escapeBackslash(value).replace("=", "\\=");
    }

    private static String escapeAttributeValue(String value) {
        return escapeBackslash(value).replace(")", "\\)").replace(";", "\\;");
    }

    private static String escapeBackslash(String value) {
        return value.replace("\\", "\\\\");
    }
}