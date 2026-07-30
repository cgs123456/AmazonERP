package com.amz.client;

import lombok.extern.slf4j.Slf4j;

/**
 * 多平台客户端抽象基类。
 * <p>
 * Temu / TikTok Shop / Shein 三个平台客户端代码重复率 90%+，故抽取公共逻辑：
 * <ul>
 *   <li>{@link #mask(String)} 凭证脱敏</li>
 *   <li>公共 logger</li>
 *   <li>平台常量</li>
 * </ul>
 * <p>
 * 子类通过 {@link #getPlatform()} 声明平台标识，用于日志与统一处理。
 */
@Slf4j
public abstract class AbstractPlatformClient {

    /** 平台标识：TEMU / TIKTOK / SHEIN */
    protected static final String PLATFORM_TEMU = "TEMU";
    protected static final String PLATFORM_TIKTOK = "TIKTOK";
    protected static final String PLATFORM_SHEIN = "SHEIN";

    /**
     * 子类返回各自平台标识，用于日志与异常定位。
     */
    protected abstract String getPlatform();

    /**
     * 凭证脱敏：保留前 2 + 后 2 位，中间用 **** 替代。
     * 用于日志输出 appKey/appSecret，避免明文泄露。
     */
    protected String mask(String key) {
        if (key == null || key.length() < 4) {
            return "****";
        }
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }
}
