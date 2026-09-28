package com.amz.bootstrap;

import com.amz.credential.ShopCredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/**
 * bootstrap profile 的批量凭证导入事务边界。
 * <p>
 * {@link SpapiCredentialBootstrapLoader} 先解析并校验整批输入，再通过
 * {@link ShopCredentialStore#putAll(java.util.List)} 写入。本服务的 {@link Transactional}
 * 保证加载器返回前所有数据库写入处于同一事务；runner 必须在事务方法返回后才退出进程，
 * 否则会出现“接口报错但事务已提交”或反向的不可判定状态。
 */
@Service
@Profile("bootstrap")
public class SpapiCredentialBootstrapImporter {

    private final ShopCredentialStore credentialStore;
    private final SpapiCredentialBootstrapLoader loader;

    public SpapiCredentialBootstrapImporter(ShopCredentialStore credentialStore,
                                            ObjectMapper objectMapper) {
        this.credentialStore = credentialStore;
        this.loader = new SpapiCredentialBootstrapLoader(objectMapper);
    }

    /**
     * 导入整批凭证；任一结构错误或数据库错误都会回滚本次事务。
     *
     * @return 成功写入的凭证数量
     */
    @Transactional
    public int importCredentials(Path credentialFile) {
        return loader.load(credentialFile, credentialStore);
    }
}
