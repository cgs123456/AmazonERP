package com.amz.model;

/**
 * Agent 多语言支持枚举。
 * <p>
 * 支持中文、英文、日文、德文四种语言，默认中文。
 */
public enum LanguageEnum {

    /** 中文 */
    ZH("中文", "You are a cross-border e-commerce Amazon ERP assistant. Reply in Chinese (简体中文)."),

    /** 英文 */
    EN("English", "You are a cross-border e-commerce Amazon ERP assistant. Reply in English."),

    /** 日文 */
    JA("日本語", "You are a cross-border e-commerce Amazon ERP assistant. Reply in Japanese (日本語)."),

    /** 德文 */
    DE("Deutsch", "You are a cross-border e-commerce Amazon ERP assistant. Reply in German (Deutsch).");

    private final String displayName;
    private final String instruction;

    LanguageEnum(String displayName, String instruction) {
        this.displayName = displayName;
        this.instruction = instruction;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getInstruction() {
        return instruction;
    }

    /**
     * 按代码解析语言枚举，未识别则返回 ZH。
     */
    public static LanguageEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return ZH;
        }
        try {
            return valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ZH;
        }
    }
}
