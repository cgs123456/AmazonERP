package com.amz.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API controller 失败出口的结构化错误守卫（P0-52a 收口）。
 * <p>
 * 有真实凭据后，调用方必须能稳定区分参数错误、越权、缺凭证和 Amazon 上游失败；
 * 只返回 {@code message} 会让客户端再次退化为字符串匹配。本测试扫描源码，
 * 要求每个 {@code Result.failure(...)} 都带 {@code ErrorSummary.localError(...)}
 * 或 {@code ErrorSummary.toApiError(...)}。
 * <p>
 * 证据类型 E1（自证：断言对象为本仓库源码本身，不发起 SP-API 请求）。
 */
@DisplayName("SP-API controller 失败出口必须携带结构化 error")
class ControllerFailureContractTest {

    private static final Path CONTROLLER_DIR = Paths.get("src/main/java/com/amz/controller");

    @Test
    @DisplayName("62 个 Result.failure 出口均有本地或上游错误分类")
    void everyFailureCarriesStructuredError() throws IOException {
        assertTrue(Files.isDirectory(CONTROLLER_DIR), "找不到 controller 源码目录：" + CONTROLLER_DIR);

        List<Path> files;
        try (Stream<Path> stream = Files.list(CONTROLLER_DIR)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .sorted()
                    .toList();
        }
        assertTrue(files.size() >= 8, "controller 源码文件数异常，扫描会假通过：" + files.size());

        List<String> violations = new ArrayList<>();
        int failureCount = 0;
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            int cursor = 0;
            while ((cursor = source.indexOf("Result.failure(", cursor)) >= 0) {
                failureCount++;
                int statementEnd = statementEnd(source, cursor);
                if (statementEnd < 0) {
                    violations.add(file.getFileName() + ": 无法定位 Result.failure 语句结束");
                    break;
                }
                String statement = source.substring(cursor, statementEnd + 1);
                if (!statement.contains("ErrorSummary.localError(")
                        && !statement.contains("ErrorSummary.toApiError(")) {
                    violations.add(file.getFileName() + ": "
                            + statement.replaceAll("\\s+", " ").trim());
                }
                cursor += "Result.failure(".length();
            }
        }

        assertEquals(62, failureCount,
                "Result.failure 数量变化必须重新审查；新增失败分支也必须携带结构化 error");
        assertTrue(violations.isEmpty(), "发现未结构化的失败出口：\n" + String.join("\n", violations));
    }

    /**
     * 定位 Java 语句分号，跳过字符串字面量内的分号（错误文案可能包含 {@code ;}）。
     */
    private static int statementEnd(String source, int start) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char current = source.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == ';') {
                return i;
            }
        }
        return -1;
    }
}

