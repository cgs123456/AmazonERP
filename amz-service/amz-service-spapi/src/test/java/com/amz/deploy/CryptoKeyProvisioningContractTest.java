package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * crypto.key 的部署侧供给契约。
 * <p>
 * <b>实测发现的缺陷（2026-09-30，镜像层 A/B）</b>：{@code CryptoUtil} 是 amz-common 里的
 * {@code @Component}，{@code @Value("${crypto.key:}")} + {@code @PostConstruct} 空值即抛
 * {@code crypto.key 未配置，拒绝启动}。但仓库只在两处给它供了值：
 * {@code spapi}/{@code user} 的 {@code application.yml} 写了
 * {@code crypto.key: ${AMZ_CRYPTO_KEY:}}，compose 也只给这两个服务传了 {@code AMZ_CRYPTO_KEY}。
 * 其余 14 个可部署服务两者都没有，于是在 master 上<b>一个都起不来</b>：
 *
 * <pre>
 * 同一环境、只差镜像：
 *   amazon-erp/amz-gateway:latest              → running  exit=0（旧代码无此约束）
 *   amazon-erp/amz-gateway:p2fix-ca561a4       → exited   exit=1
 *     Caused by: java.lang.IllegalStateException: crypto.key 未配置，拒绝启动。（CryptoUtil.init:53）
 * 补一个 CRYPTO_KEY 环境变量后：
 *   amazon-erp/amz-gateway:p2fix-ca561a4       → running  exit=0，Started AmzGatewayApplication in 16.294s
 * </pre>
 * <p>
 * 环境变量名必须是 {@code CRYPTO_KEY}：Spring 的宽松绑定把它落到 {@code crypto.key}；
 * {@code AMZ_CRYPTO_KEY} 不会自动落到该属性（只有那两个服务用 yml 显式映射过），
 * 而异常文案却让运维去设 {@code AMZ_CRYPTO_KEY}——照文案设值的服务依旧起不来。
 * 这条不匹配由 {@link #codeSideLooksUpCryptoKeyProperty} 守住。
 * <p>
 * 另注意：把 {@code CryptoUtil} 改成 {@code @ConditionalOnProperty("crypto.key")} 看似也能解决启动问题，
 * 但 {@code CryptoTypeHandler}（amz-common）是<b>静态</b>取 {@code CryptoUtil.getInstance()} 的，
 * 属性缺失时容器起得来、读写加密列才炸——故障从启动期挪到运行期，比现在更难发现。
 * 因此本契约要求的是"把密钥供给齐"，不是"把校验关掉"。
 */
@DisplayName("部署契约：每个可部署服务都必须被供给 crypto.key")
class CryptoKeyProvisioningContractTest {

    private static final Path ROOT = findRepoRoot();

    private static final Set<String> DEPLOYABLE = Set.of(
            "amz-gateway", "amz-service-ad", "amz-service-ai", "amz-service-customer",
            "amz-service-finance", "amz-service-logistics", "amz-service-message",
            "amz-service-multiplatform", "amz-service-ops", "amz-service-order",
            "amz-service-procurement", "amz-service-product", "amz-service-report",
            "amz-service-search", "amz-service-spapi", "amz-service-user");

    /** 能落到 crypto.key 的两种写法：宽松绑定的 CRYPTO_KEY，或经 yml 映射的 AMZ_CRYPTO_KEY。 */
    private static final List<String> ACCEPTED_ENV =
            List.of("- CRYPTO_KEY=", "- AMZ_CRYPTO_KEY=");

    @Test
    @DisplayName("compose 的 16 个可部署服务全部供给加密密钥")
    void composeProvidesCryptoKeyForEveryDeployableService() throws IOException {
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String service : DEPLOYABLE) {
            String block = composeBlock(compose, service);
            if (ACCEPTED_ENV.stream().noneMatch(block::contains)) {
                missing.add(service);
            }
        }
        assertEquals(List.of(), missing,
                "这些服务会带着空 crypto.key 启动并当场拒绝（见类注释里的 A/B 实测）");
    }

    @Test
    @DisplayName("k8s 的 16 份清单全部供给加密密钥，且引用已有的 secret 键")
    void kubernetesProvidesCryptoKeyForEveryDeployableService() throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> wrongSource = new ArrayList<>();
        for (String service : DEPLOYABLE) {
            Path manifest = ROOT.resolve("k8s").resolve("services").resolve(service + ".yaml");
            assertTrue(Files.isRegularFile(manifest), "缺少清单：" + manifest);
            boolean found = false;
            for (Object document : new Yaml().loadAll(read(manifest))) {
                Map<String, Object> doc = castMap(document);
                if (!"Deployment".equals(doc.get("kind"))) {
                    continue;
                }
                for (Map<String, Object> env : containerEnv(doc)) {
                    String name = String.valueOf(env.get("name"));
                    if (!"CRYPTO_KEY".equals(name) && !"AMZ_CRYPTO_KEY".equals(name)) {
                        continue;
                    }
                    found = true;
                    Object from = env.get("valueFrom");
                    if (from == null) {
                        wrongSource.add(service + "（密钥不能以明文 value 写进清单）");
                    }
                }
            }
            if (!found) {
                missing.add(service);
            }
        }
        assertEquals(List.of(), missing, "k8s 清单缺加密密钥供给，Pod 会 CrashLoopBackOff");
        assertEquals(List.of(), wrongSource, "清单里出现明文字面量：" + wrongSource);
    }

    @Test
    @DisplayName("代码侧查的属性名仍是 crypto.key，否则本测试的环境变量名失去依据")
    void codeSideLooksUpCryptoKeyProperty() throws IOException {
        String source = Files.readString(ROOT.resolve(
                "amz-common/src/main/java/com/amz/util/CryptoUtil.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("@Value(\"${crypto.key:}\")"),
                "CryptoUtil 查的属性变了：本契约认 CRYPTO_KEY 这个环境名的前提就不成立了");
        assertTrue(source.contains("@Component"),
                "CryptoUtil 若不再是 @Component，供给面要按新的装配条件重新核算");
    }

    @Test
    @DisplayName("gateway 单点回归：它没有 yml 映射，只能靠 CRYPTO_KEY 这个名字")
    void gatewayUsesTheRelaxedBindingName() throws IOException {
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
        assertTrue(composeBlock(compose, "amz-gateway").contains("- CRYPTO_KEY=${AMZ_CRYPTO_KEY:?"),
                "gateway 必须由 CRYPTO_KEY 供给（值仍取自部署方的 AMZ_CRYPTO_KEY，密钥不落两处）");
        String gatewayYaml = Files.readString(
                ROOT.resolve("amz-gateway/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertTrue(!gatewayYaml.contains("crypto:"),
                "gateway 若开始用 yml 映射 crypto.key，需同步复核本契约允许的两种写法");
    }

    // ------------------------------------------------------------------ helpers

    private static String composeBlock(String compose, String service) {
        int start = compose.indexOf("\n  " + service + ":\n");
        assertTrue(start >= 0, "docker-compose.yml 里找不到服务段：" + service);
        int next = compose.indexOf("\n  amz-", start + 1);
        for (String marker : List.of("\n  frontend:", "\n  mysql", "\n  redis", "\n  nacos")) {
            int at = compose.indexOf(marker, start + 1);
            if (at >= 0 && (next < 0 || at < next)) {
                next = at;
            }
        }
        return next < 0 ? compose.substring(start) : compose.substring(start, next);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> containerEnv(Map<String, Object> deployment) {
        Map<String, Object> spec = castMap(
                castMap(castMap(deployment.get("spec")).get("template")).get("spec"));
        List<Map<String, Object>> envs = new ArrayList<>();
        for (Object container : (List<Object>) spec.getOrDefault("containers", List.of())) {
            Map<String, Object> c = castMap(container);
            Object env = c.get("env");
            if (env instanceof List) {
                for (Object item : (List<Object>) env) {
                    envs.add(castMap(item));
                }
            }
        }
        return envs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("k8s"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
