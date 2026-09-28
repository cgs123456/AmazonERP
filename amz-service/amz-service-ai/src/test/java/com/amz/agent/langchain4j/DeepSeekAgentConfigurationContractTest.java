package com.amz.agent.langchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeepSeek Agent 配置契约")
class DeepSeekAgentConfigurationContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LangChain4jAgentConfig.class);

    @Test
    @DisplayName("application.yml 使用 kebab-case，避免 @Value 无法解析 snake_case")
    void applicationYamlUsesCanonicalKebabCaseKeys() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .anySatisfy(source -> assertThat(source.containsProperty("deepseek.api-key")).isTrue())
                .anySatisfy(source -> assertThat(source.containsProperty("deepseek.api-url")).isTrue())
                .noneSatisfy(source -> assertThat(source.containsProperty("deepseek.api_key")).isTrue())
                .noneSatisfy(source -> assertThat(source.containsProperty("deepseek.api_url")).isTrue());
    }

    @Test
    @DisplayName("设置 DEEPSEEK_API_KEY 后必须创建模型与 ERP Agent")
    void createsAgentBeansWhenApiKeyIsConfigured() {
        contextRunner
                .withPropertyValues("DEEPSEEK_API_KEY=test-deepseek-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("chatLanguageModel");
                    assertThat(context).hasBean("erpAgent");
                });
    }

    @Test
    @DisplayName("DEEPSEEK_API_KEY 为空时不得创建模型与 ERP Agent")
    void doesNotCreateAgentBeansWhenApiKeyIsBlank() {
        contextRunner
                .withPropertyValues("DEEPSEEK_API_KEY=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("chatLanguageModel");
                    assertThat(context).doesNotHaveBean("erpAgent");
                });
    }
}