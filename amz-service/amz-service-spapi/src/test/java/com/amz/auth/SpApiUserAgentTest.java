package com.amz.auth;

import com.amz.config.SpApiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-35：官方必填头 {@code user-agent} 的拼装、转义与长度上限。
 * <p>
 * 期望值来源（一手，2026-09-24 抓取）：官方
 * {@code connecting-to-the-selling-partner-api} 页 “How to assemble a user-agent header”：
 * <ul>
 *   <li>拼接顺序：应用名 + {@code /} + 版本 + 空格 + {@code (} + 语言名值对 + {@code )}</li>
 *   <li>官方示例逐字：{@code MySellingTool/2.0 (Language=Java/1.8.0.221; Platform=Windows/10)}</li>
 *   <li>转义（用反斜杠）：反斜杠；应用名中的 {@code /}；版本中的 {@code (}；属性名中的 {@code =}；属性值中的 {@code )} 与 {@code ;}</li>
 *   <li>最大长度 500 字符；最小必须含 App 名、版本、语言</li>
 * </ul>
 */
@DisplayName("P0-35 user-agent 官方拼装与转义规则")
class SpApiUserAgentTest {

    @Test
    @DisplayName("官方示例逐字复现")
    void composesOfficialExample() {
        assertEquals("MySellingTool/2.0 (Language=Java/1.8.0.221; Platform=Windows/10)",
                SpApiUserAgent.compose("MySellingTool", "2.0", "Java/1.8.0.221", "Windows/10"));
    }

    @Test
    @DisplayName("无 Platform 属性时省略第二个属性，最小信息仍齐全")
    void composesMinimalForm() {
        assertEquals("AmazonERP/1.0 (Language=Java/17.0.20.1)",
                SpApiUserAgent.compose("AmazonERP", "1.0", "Java/17.0.20.1", null));
    }

    @Test
    @DisplayName("按官方规则转义：反斜杠 / 应用名斜杠 / 版本左括号 / 属性名等号 / 属性值右括号与分号")
    void escapesPerOfficialRules() {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Language", "Java");
        attributes.put("X=Y", "a;b)c");
        // 输入：应用名含 "/" 与 "\"，版本含 "("；属性名含 "="；属性值含 ";" 与 ")"
        String ua = SpApiUserAgent.compose("Amz/ERP\\Pro", "1.0(beta", attributes);
        assertEquals("Amz\\/ERP\\\\Pro/1.0\\(beta (Language=Java; X\\=Y=a\\;b\\)c)", ua);
    }

    @Test
    @DisplayName("超过 500 字符必须拒绝，禁止静默截断")
    void rejectsOverlong() {
        String longName = "A".repeat(600);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SpApiUserAgent.compose(longName, "1.0", "Java/17", null));
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    @DisplayName("应用名/版本为空必须 fail-closed")
    void rejectsBlankAppNameOrVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> SpApiUserAgent.compose("   ", "1.0", "Java/17", null));
        assertThrows(IllegalArgumentException.class,
                () -> SpApiUserAgent.compose("AmazonERP", "", "Java/17", null));
        assertThrows(IllegalArgumentException.class,
                () -> SpApiUserAgent.compose("AmazonERP", "1.0", "  ", null));
    }

    @Test
    @DisplayName("value()：显式 spapi.user-agent 覆盖优先，否则按配置字段拼装")
    void valueUsesConfigAndOverride() {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setPlatform("Windows/10");

        SpApiUserAgent agent = new SpApiUserAgent(config);
        assertEquals("AmazonERP/1.0-SNAPSHOT (Language=Java/17.0.20.1; Platform=Windows/10)", agent.value());

        config.setUserAgent("Custom/9.9 (Language=Java/17)");
        assertEquals("Custom/9.9 (Language=Java/17)", agent.value());
    }

    @Test
    @DisplayName("默认配置即可产出合法头：≤500、含 App 名/版本/语言")
    void defaultConfigProducesValidHeader() {
        SpApiUserAgent agent = new SpApiUserAgent(new SpApiConfig());
        String ua = agent.value();
        assertTrue(ua.length() <= SpApiUserAgent.MAX_LENGTH, ua);
        assertTrue(ua.startsWith("AmazonERP/"), ua);
        assertTrue(ua.contains("Language="), ua);
    }
}
