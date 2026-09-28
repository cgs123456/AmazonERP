package com.amz.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * bootstrap profile 的一次性凭证导入进程。
 * <p>
 * 该进程不启动 Web 容器，只读取 {@code SPAPI_BOOTSTRAP_CREDENTIAL_FILE} 指向的 JSON，
 * 通过 {@link SpapiCredentialBootstrapImporter} 在事务内加密落库后退出。普通生产实例
 * 仍使用 prod profile，并在凭证为空或结构残缺时拒绝启动。
 */
@Slf4j
@Component
@Profile("bootstrap")
public class SpapiCredentialBootstrapRunner implements ApplicationRunner {

    private final SpapiCredentialBootstrapImporter importer;
    private final ConfigurableApplicationContext applicationContext;
    private final String credentialFile;
    private final boolean exitAfterLoad;

    public SpapiCredentialBootstrapRunner(
            SpapiCredentialBootstrapImporter importer,
            ConfigurableApplicationContext applicationContext,
            @Value("${spapi.bootstrap.credential-file:}") String credentialFile,
            @Value("${spapi.bootstrap.exit-after-load:true}") boolean exitAfterLoad) {
        this.importer = importer;
        this.applicationContext = applicationContext;
        this.credentialFile = credentialFile;
        this.exitAfterLoad = exitAfterLoad;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (credentialFile == null || credentialFile.isBlank()) {
            throw new IllegalStateException("bootstrap 模式必须配置 SPAPI_BOOTSTRAP_CREDENTIAL_FILE");
        }

        int count = importer.importCredentials(Path.of(credentialFile.trim()));
        log.info("[SpapiCredentialBootstrapRunner] 已导入并加密落库 {} 条店铺凭证。", count);

        if (exitAfterLoad) {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }
}
