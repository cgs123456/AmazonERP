package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dockerfile 的 Maven 依赖缓存挂载契约。
 * <p>
 * 存在动因（本机实测）：未加挂载时 order 镜像连续两次构建失败，且各失败在<b>不同</b>构件上 —
 * {@code net.bytebuddy:byte-buddy-agent:jar:1.17.8}、然后
 * {@code org.apache.httpcomponents.core5:httpcore5:jar:5.4.3}，报错都是
 * {@code Could not transfer artifact ... Remotely closed}；第三次重试才通过。
 * 原因是每次构建都要把整棵依赖树重新拉一遍，Windows + Docker Desktop 的密集传输会被切断，
 * 一次抖动就白跑十几分钟。
 * <p>
 * 加挂载后同一台机器上的实测对比：
 * <ul>
 *   <li>冷缓存（挂载为空）：{@code rc=0}，734s；</li>
 *   <li>热缓存换模块逼 {@code package} 重跑：{@code rc=0}，45s，无 transfer 失败。</li>
 * </ul>
 * 注：命令带 {@code mvn -q}，日志里不会出现下载行，所以判据是耗时与失败次数，不是"下载条数=0"。
 * <p>
 * 本用例只保证"这句配置不会被无声删掉"（回归会重新引入上面那类抖动），
 * 真正的构建行为由 CI 的 docker job 承担 —— 它是全新 runner，每次都是冷缓存，
 * 因此这条路径在 CI 上与改动前等价，不会变慢。
 */
@DisplayName("Dockerfile Maven 依赖缓存挂载")
class DockerfileBuildCacheContractTest {

    private static final String MOUNT = "--mount=type=cache,target=/root/.m2";

    @Test
    @DisplayName("两条 mvn 构建命令都走 /root/.m2 缓存挂载")
    void mavenBuildStepsUseCacheMount() throws IOException {
        List<String> lines = Files.readAllLines(root().resolve("Dockerfile"), StandardCharsets.UTF_8);
        List<String> mvnRuns = lines.stream()
                .filter(line -> line.startsWith("RUN") && line.contains(" mvn "))
                .toList();
        // 扫不到 mvn 步骤＝Dockerfile 结构变了，此时"全部带挂载"会空泛成立，必须先否掉
        assertEquals(2, mvnRuns.size(),
                "预期 Dockerfile 里有 go-offline 与 package 两条 mvn 命令，实际：" + mvnRuns);
        List<String> missing = mvnRuns.stream().filter(line -> !line.contains(MOUNT)).toList();
        assertTrue(missing.isEmpty(),
                "以下 Maven 步骤没有依赖缓存挂载，会回到「一次网络抖动整场构建白跑」：" + missing);
    }

    @Test
    @DisplayName("挂载只服务构建缓存，不得把 ~/.m2 烘进镜像层")
    void cacheMountIsNotBakedIntoTheImage() throws IOException {
        String dockerfile = Files.readString(root().resolve("Dockerfile"), StandardCharsets.UTF_8);
        int finalStage = dockerfile.lastIndexOf("FROM eclipse-temurin");
        assertTrue(finalStage > 0, "找不到运行阶段的基础镜像声明");
        assertFalse(dockerfile.substring(finalStage).contains(MOUNT),
                "缓存挂载只应出现在构建阶段；出现在最终阶段说明层结构被改坏了");
    }

    private static Path root() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
