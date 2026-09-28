package com.amz.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首次部署 bootstrap 路径的契约：一次性、无端口、只读挂载、失败不自动重试。
 */
class BootstrapDeploymentContractTest {

    private static final Path ROOT = findRepoRoot();

    @Test
    void composeBootstrapIsOneShotAndMountsCredentialReadOnly() throws IOException {
        Map<String, Object> root = loadYaml(ROOT.resolve("docker-compose.bootstrap.yml"));
        Map<String, Object> services = castMap(root.get("services"));
        Map<String, Object> service = castMap(services.get("amz-service-spapi-bootstrap"));
        assertFalse(service.isEmpty(), "Compose 必须声明 amz-service-spapi-bootstrap");

        assertEquals(List.of("bootstrap"), service.get("profiles"),
                "bootstrap 服务必须使用 Compose profile，避免普通 up 时执行导入");
        assertEquals("no", String.valueOf(service.get("restart")),
                "一次性导入失败后不得自动重启");
        assertFalse(service.containsKey("ports"), "bootstrap 不得暴露任何端口");

        Map<String, String> environment = environmentValues(service.get("environment"));
        assertEquals("bootstrap", environment.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("/run/secrets/spapi-credentials.json", environment.get("SPAPI_BOOTSTRAP_CREDENTIAL_FILE"));
        assertEquals("true", environment.get("SPAPI_BOOTSTRAP_EXIT_AFTER_LOAD"));

        Map<String, Object> dependsOn = castMap(service.get("depends_on"));
        Map<String, Object> mysql = castMap(dependsOn.get("mysql"));
        assertEquals("service_healthy", String.valueOf(mysql.get("condition")),
                "bootstrap 必须在 MySQL healthcheck 通过后启动");

        List<Object> volumes = castList(service.get("volumes"));
        assertTrue(volumes.stream().map(String::valueOf).anyMatch(value ->
                        value.startsWith("${SPAPI_BOOTSTRAP_CREDENTIAL_FILE_HOST")
                                && value.endsWith(":/run/secrets/spapi-credentials.json:ro")),
                "凭证文件必须以只读方式从宿主机挂载，且路径由环境变量提供");
    }

    @Test
    void kubernetesBootstrapJobUsesExternalSecretAndNeverRetries() throws IOException {
        Path manifest = ROOT.resolve("k8s/jobs/amz-service-spapi-credential-bootstrap.yaml");
        assertTrue(Files.isRegularFile(manifest), "缺少 K8s bootstrap Job 清单");

        Map<String, Object> job = firstDocumentOfKind(manifest, "Job");
        Map<String, Object> metadata = castMap(job.get("metadata"));
        assertEquals("amz-service-spapi-credential-bootstrap", String.valueOf(metadata.get("name")));

        Map<String, Object> spec = castMap(job.get("spec"));
        assertEquals(0, ((Number) spec.get("backoffLimit")).intValue(),
                "凭证导入失败必须保留现场，不能靠重试掩盖错误");
        assertTrue(((Number) spec.get("activeDeadlineSeconds")).intValue() <= 900,
                "一次性导入必须有有界超时");

        Map<String, Object> template = castMap(spec.get("template"));
        Map<String, Object> podSpec = castMap(template.get("spec"));
        assertEquals("Never", String.valueOf(podSpec.get("restartPolicy")));

        Map<String, Object> container = castMap(castList(podSpec.get("containers")).get(0));
        Map<String, String> environment = environmentValues(container.get("env"));
        assertEquals("bootstrap", environment.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("/etc/spapi-bootstrap/credentials.json", environment.get("SPAPI_BOOTSTRAP_CREDENTIAL_FILE"));
        assertEquals("true", environment.get("SPAPI_BOOTSTRAP_EXIT_AFTER_LOAD"));

        Map<String, Object> volumeMount = castMap(castList(container.get("volumeMounts")).get(0));
        assertEquals("/etc/spapi-bootstrap", String.valueOf(volumeMount.get("mountPath")));
        assertEquals(true, volumeMount.get("readOnly"));

        Map<String, Object> volume = castMap(castList(podSpec.get("volumes")).get(0));
        Map<String, Object> secret = castMap(volume.get("secret"));
        assertEquals("amz-spapi-credential-bootstrap", String.valueOf(secret.get("secretName")));
        assertEquals(256, ((Number) secret.get("defaultMode")).intValue(),
                "K8s 挂载的凭证文件权限必须是 0400");

        String raw = Files.readString(manifest, StandardCharsets.UTF_8);
        assertFalse(raw.contains("clientSecret"), "Job 清单不得内嵌明文凭证字段");
        assertFalse(raw.contains("refreshToken"), "Job 清单不得内嵌明文凭证字段");
        assertFalse(raw.contains("clientId"), "Job 清单不得内嵌明文凭证字段");
    }

    private static Map<String, Object> firstDocumentOfKind(Path path, String kind) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (Object document : new Yaml().loadAll(reader)) {
                if (document instanceof Map<?, ?> map && kind.equals(map.get("kind"))) {
                    return castMap(map);
                }
            }
        }
        throw new AssertionError("未找到 kind=" + kind + " 的文档：" + path);
    }

    private static Map<String, String> environmentValues(Object environment) {
        Map<String, String> result = new LinkedHashMap<>();
        if (environment instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
            return result;
        }
        if (environment instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("name") != null) {
                    result.put(String.valueOf(map.get("name")),
                            map.get("value") == null ? "" : String.valueOf(map.get("value")));
                    continue;
                }
                String raw = String.valueOf(item);
                int equals = raw.indexOf('=');
                if (equals >= 0) {
                    result.put(raw.substring(0, equals), raw.substring(equals + 1));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("k8s/services"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}