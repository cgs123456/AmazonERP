package com.amz.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiConfig {

    @Value("${spring.application.name:amz-service}")
    private String appName;

    @Bean
    public OpenAPI amzOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(appName + " API")
                        .description("Amazon ERP 微服务 API 文档")
                        .version("v3.0.0")
                        .contact(new Contact()
                                .name("Amazon ERP Team")
                                .email("support@amz-erp.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:{serverPort}").description("本地开发")
                                .variables(new io.swagger.v3.oas.models.servers.ServerVariables()
                                        .addServerVariable("serverPort",
                                                new io.swagger.v3.oas.models.servers.ServerVariable()
                                                        ._default("8080")
                                                        .description("服务端口")))
                ));
    }
}
