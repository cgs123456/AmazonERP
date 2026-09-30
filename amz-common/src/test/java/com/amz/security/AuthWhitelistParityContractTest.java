package com.amz.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 免鉴权白名单的两侧一致性：网关 {@code MyGlobalFilter.WHITE_LIST} 与服务层
 * {@code BaseAuthInterceptor.WHITE_LIST}。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查）</b>：网关放行 {@code /user/refresh}，服务层白名单没有它
 * （注释却自称"与网关保持一致"）。刷新 access token 时手上的 token 正是已过期的，
 * 请求过得了网关却被服务层拦下——直连运行中的 user 服务实测
 * {@code POST /user/refresh}（不带 token）返回 <b>401</b>，来自服务层拦截器而不是
 * controller 的"只接受 refresh token"业务错误。结果是用户永远续不了期，只能重新短信登录。
 * <p>
 * 两个列表分处两个模块、各写一份，改一边忘一边是结构性的，所以这里核对<b>集合并等</b>，
 * 而不是"包含某项"这种补上就完的断言。
 */
@DisplayName("鉴权契约：网关与服务层的免鉴权白名单必须是同一集合")
class AuthWhitelistParityContractTest {

    private static final Path ROOT = findRepoRoot();

    private static final Path GATEWAY_FILTER =
            ROOT.resolve("amz-gateway/src/main/java/com/amz/filter/MyGlobalFilter.java");
    private static final Path COMMON_INTERCEPTOR =
            ROOT.resolve("amz-common/src/main/java/com/amz/interceptor/BaseAuthInterceptor.java");

    private static final String DECL = "List<String> WHITE_LIST = List.of(";
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");

    @Test
    @DisplayName("两侧白名单集合完全相等")
    void gatewayAndServiceLayerWhitelistsAreIdentical() throws IOException {
        Set<String> gateway = whiteListEntries(GATEWAY_FILTER);
        Set<String> services = whiteListEntries(COMMON_INTERCEPTOR);

        // 解析退化时两侧可能同时得到空集并"相等"，所以先要求各非空
        assertTrue(gateway.size() >= 3, "没从网关解析出白名单项，取样点已失效：" + gateway);
        assertTrue(services.size() >= 3, "没从服务层解析出白名单项，取样点已失效：" + services);

        assertEquals(gateway, services,
                "仅网关放行 = 到了服务层照样 401；仅服务层放行 = 绕过网关即可免鉴权访问。"
                        + "仅网关有=" + difference(gateway, services)
                        + "，仅服务层有=" + difference(services, gateway));
    }

    @Test
    @DisplayName("refresh 两侧同时免鉴权，且它确实是 user 服务上的真实路由")
    void refreshTokenEndpointIsReachableWithoutAccessToken() throws IOException {
        assertTrue(whiteListEntries(GATEWAY_FILTER).contains("/user/refresh"),
                "网关未放行 /user/refresh");
        assertTrue(whiteListEntries(COMMON_INTERCEPTOR).contains("/user/refresh"),
                "服务层未放行 /user/refresh：过期 access token 后无法续期（实测 401）");

        String controller = Files.readString(ROOT.resolve(
                        "amz-service/amz-service-user/src/main/java/com/amz/controller/LoginController.java"),
                StandardCharsets.UTF_8);
        assertTrue(controller.contains("@PostMapping(\"/refresh\")"),
                "/user/refresh 的 controller 映射已变：白名单留着这条等于开一个无主免鉴权路径");
    }

    // ------------------------------------------------------------------ helpers

    /** 取 WHITE_LIST 声明里的字符串字面量；跳过注释行，避免把说明文字当成条目。 */
    private static Set<String> whiteListEntries(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        int declaredAt = source.indexOf(DECL);
        assertTrue(declaredAt >= 0, "找不到 WHITE_LIST 声明：" + file);

        String afterOpen = source.substring(declaredAt + DECL.length());
        int closedAt = afterOpen.indexOf("\n    );");
        assertTrue(closedAt > 0, "WHITE_LIST 声明未按预期闭合，解析规则需要更新：" + file);

        String body = afterOpen.substring(0, closedAt).lines()
                .filter(line -> !line.trim().startsWith("//"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        Set<String> entries = new LinkedHashSet<>();
        Matcher literal = STRING_LITERAL.matcher(body);
        while (literal.find()) {
            entries.add(literal.group(1));
        }
        return entries;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> remaining = new LinkedHashSet<>(left);
        remaining.removeAll(right);
        return remaining;
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isDirectory(current.resolve("amz-gateway"))
                    && Files.isRegularFile(current.resolve("docker-compose.yml"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
