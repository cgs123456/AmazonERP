package com.amz.agent;

import com.amz.model.LanguageEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 多语言提示词构建器。
 * <p>
 * 在原 ErpSystemPromptBuilder 的工具清单基础上，根据用户语言偏好追加语言指令，
 * 使 Agent 回复自动切换为中文/英文/日文/德文。
 */
@Component
public class MultiLangPromptBuilder {

    @Autowired
    private ErpSystemPromptBuilder baseBuilder;

    /**
     * 构建带语言偏好的系统提示词。
     *
     * @param language 语言枚举
     * @return 系统提示词
     */
    public String build(LanguageEnum language) {
        String base = baseBuilder.build();
        if (language == null || language == LanguageEnum.ZH) {
            // 中文为默认，不追加额外指令
            return base;
        }
        return base + "\n\n【语言要求】\n" + language.getInstruction();
    }

    /**
     * 按语言代码构建（兼容字符串入参）。
     */
    public String build(String languageCode) {
        return build(LanguageEnum.fromCode(languageCode));
    }
}
