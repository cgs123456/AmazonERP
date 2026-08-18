# 6b：全局密钥外部化方案（Nacos + CryptoUtil）

> 日期：2026-08-18 ｜ 范围：全项目密钥/敏感配置审计与外部化方案

---

## 1. 现状审计

### 1.1 已接入的基础设施

- **Spring Cloud Alibaba Nacos**：所有模块 `application.yml` 已配置 `spring.cloud.nacos.config` 和 `spring.cloud.nacos.discovery`
- **CryptoUtil**（`amz-common`）：AES-256-GCM 加解密，密钥来自环境变量 `AMZ_CRYPTO_KEY`
- **ShopCredentialStore**（spapi）：已使用 CryptoUtil 对 SP-API 凭证加密落库
- **PlatformCredentialService**（multiplatform）：同上，读取 `amz_platform_account` 加密列

### 1.2 密钥/敏感配置清单（按风险分级）

| 优先级 | 密钥/配置项 | 注入位置 | 当前来源 | 风险 |
|--------|------------|---------|---------|------|
| **P0** | `deepseek.api_key` | `AiServiceImpl`, `SelectionAnalysisServiceImpl`, `ReviewAnalysisServiceImpl`, `LangChain4jAgentConfig`（×4 处） | `@Value` 明文 yaml | 🔴 高：LLM 调用凭据，泄露可盗用额度 |
| **P0** | `oss.accessKeySecret` | `OssConfig` | `@Value` 明文 yaml | 🔴 高：OSS 写权限凭据 |
| **P1** | `alibaba.app-secret` | `Alibaba1688RealClient` | `@Value` 明文 yaml | 🟠 中：1688 平台签名密钥 |
| **P1** | `alibaba.refresh-token` | `Alibaba1688RealClient` | `@Value` 明文 yaml | 🟠 中：1688 Token 刷新凭据 |
| **P1** | `spapi.lwa.access-token` | `MessagingApiRealClient`, `AdvertisingApiRealClient` | `@Value` 明文 yaml | 🟠 中：SP-API 调用凭据 |
| **P1** | `kingdee.app-secret` | `KingdeeRealClient` | `@Value` 明文 yaml | 🟡 低：金蝶财务 API 凭据 |
| **P2** | `spring.redis.password` | 3 处 `RedissonConfig` | `@Value` 明文 yaml | 🟡 低：Redis 连接密码 |
| **P2** | `keepa.api-key` | `KeepaRealClient` | `@Value` 明文 yaml | 🟢 低：第三方数据服务 API Key |

### 1.3 非敏感配置（无需外部化）

以下配置为端点地址或业务参数，不涉及凭据，保留本地 yml 或环境变量即可：
- `deepseek.api_url` / `deepseek.base-url` / `deepseek.model-name` / `deepseek.temperature` / `deepseek.timeout`
- `oss.endpoint` / `oss.bucketName` / `oss.accessKeyId` / `oss.access-url`
- `spring.redis.host` / `spring.redis.port`
- `alibaba.gateway`
- `advertising.api-endpoint` / `advertising.profile-id`
- `spapi.messaging.endpoint`
- `agent.reminder.*` 阈值参数
- `amz.exchange-rates` / `amz.http.*`

---

## 2. 方案设计

### 2.1 策略：三级治理

| 级别 | 处理方式 | 适用密钥 |
|------|---------|---------|
| **A**（强外部化） | Nacos 明文配置 + 环境变量覆盖 | 无 |
| **B**（加密外部化） | Nacos 密文配置 + CryptoUtil 解密 | deepseek.api_key、oss.accessKeySecret |
| **C**（安全存储） | 数据库加密列 + ShopCredentialStore/PlatformCredentialService 模式 | spapi.lwa.access-token、alibaba.*、kingdee.* |
| **D**（环境变量） | 容器/CI 环境变量注入，不写 yml | redis.password（高安全场景） |

### 2.2 实施步骤

#### 步骤 1：Nacos 配置迁移

将敏感配置从各模块 `application.yml` 迁移到 Nacos：

```yaml
# Nacos Data ID: amz-service-ai-dev.yaml（示例）
deepseek:
  api-key: ENC(AES-GCM,cipherText=xxxxx,iv=xxxxx)  # 经 CryptoUtil 加密
  api_url: https://api.deepseek.com/v1
  base-url: https://api.deepseek.com/v1
  model-name: deepseek-chat
  temperature: 0.7
  timeout: 60

# Nacos Data ID: amz-service-user-dev.yaml
oss:
  endpoint: oss-cn-hangzhou.aliyuncs.com
  bucketName: amz-erp
  accessKeyId: LTAI5xxx
  accessKeySecret: ENC(AES-GCM,cipherText=xxxxx,iv=xxxxx)
  access-url: https://amz-erp.oss-cn-hangzhou.aliyuncs.com
```

**加密方式**：使用现有 `CryptoUtil.encrypt(plaintext)` 生成密文，写入 Nacos 时前缀 `ENC(...)` 标识。启动时 `@PostConstruct` 解密后注入。

#### 步骤 2：代码改造

**方案一：`@NacosValue` + 自动解密（推荐）**

在 `amz-common` 新增 `NacosEncryptedValueProcessor`：

```java
@Configuration
public class NacosConfigAutoConfiguration {

    @Bean
    public static NacosValuePostProcessor nacosValuePostProcessor() {
        return new NacosValuePostProcessor();
    }
}

public class NacosValuePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 扫描 @Value 注解，识别 ENC(...) 前缀的密文值
        // 使用 CryptoUtil.decrypt() 解密后设置
        return bean;
    }
}
```

**方案二：配置类集中管理（更保守）**

新建 `SecureConfigProperties` 类，统一接收 Nacos 配置并解密：

```java
@Data
@ConfigurationProperties(prefix = "amz.security")
public class SecureConfigProperties {
    private String deepseekApiKey;
    private String ossAccessKeySecret;

    @PostConstruct
    public void init() {
        if (deepseekApiKey != null && deepseekApiKey.startsWith("ENC(")) {
            deepseekApiKey = CryptoUtil.decrypt(deepseekApiKey);
        }
    }
}
```

然后各模块通过 `@Autowired SecureConfigProperties` 获取。

#### 步骤 3：环境变量兜底

所有密钥同时支持环境变量覆盖：

```yaml
# application.yml 中的默认值（仅用于本地开发）
deepseek:
  api-key: ${DEEPSEEK_API_KEY:}  # 优先读环境变量
```

### 2.3 各密钥具体处理方案

| 密钥 | 方案 | 原因 |
|------|------|------|
| `deepseek.api_key` | **B**（Nacos 密文） | 最高风险，4 处注入需统一替换 |
| `oss.accessKeySecret` | **B**（Nacos 密文） | 唯一 OSS 写入凭据 |
| `alibaba.app-secret` | **C**（数据库） | 已有 1688 凭证表，可扩展 api_secret_encrypted 列 |
| `alibaba.refresh-token` | **C**（数据库） | 同上 |
| `spapi.lwa.access-token` | **C**（数据库） | 已有 `amz_shop_credential` 表，复用 ShopCredentialStore |
| `kingdee.app-secret` | **C**（数据库） | 可新建 `amz_kingdee_account` 表，模式同 PlatformAccount |
| `spring.redis.password` | **D**（环境变量） | Redis 密码通过容器编排注入，不走配置中心 |
| `keepa.api-key` | **A**（Nacos 明文） | 第三方数据服务 Key，风险较低，Nacos 明文即可 |

---

## 3. 工作量评估

| 步骤 | 工作量 | 依赖 |
|------|--------|------|
| 1. Nacos 密文配置写入 | 0.5 人日 | 无 |
| 2. 新增 SecureConfigProperties 或 NacosValuePostProcessor | 1 人日 | 步骤1 |
| 3. 替换 4 处 deepseek.api_key 注入 | 0.5 人日 | 步骤2 |
| 4. 替换 oss.accessKeySecret 注入 | 0.25 人日 | 步骤2 |
| 5. 1688 凭证表扩展 + 注入替换 | 0.5 人日 | 步骤4（复用 PlatformCredentialService 模式） |
| 6. SP-API LWA Token 复用 ShopCredentialStore | 0.25 人日 | 已有实现 |
| 7. 金蝶账号表 + 注入替换 | 0.5 人日 | 步骤5（参照 PlatformAccount 模式） |
| 8. Redis 密码环境变量化 | 0.1 人日 | 无 |
| 9. Keepa API Key Nacos 配置 | 0.1 人日 | 无 |
| 10. 测试与验证 | 1 人日 | 全部步骤完成 |
| **合计** | **约 3.7 人日** | 可分 2 批实施 |

---

## 4. 风险控制

- **向后兼容**：本地 yml 中的明文默认值保留，生产通过 Nacos 覆盖；两者都缺时 `@ConditionalOnProperty` 降级（如 LangChain4jAgentConfig 已有此机制）
- **灰度发布**：先在 staging 环境写入 Nacos 密文，验证解密正常后再切生产
- **密钥轮换**：Nacos 支持配置动态刷新，轮换时无需重启服务
- **审计日志**：Nacos 有操作审计；CryptoUtil 记录解密失败日志（含 shopId/platform 上下文）

---

## 5. 不在本次范围内

- **Git 仓库 `.env` 文件清理**：需人工确认是否有历史 commits 泄露密钥，建议另开安全审计
- **Nacos Server 自身安全加固**：鉴权、 namespace 隔离由运维侧负责
- **KMS 集成**：如后续需 AWS KMS / 阿里云 KMS 托管主密钥，可在此方案基础上叠加
