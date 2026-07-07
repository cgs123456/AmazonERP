# 笔记内容审核 Agent 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在笔记发布 Saga 责任链中插入 LLM 内容审核 Handler，自动检测图文违规内容，高置信度自动拦截、中置信度标记人工复审、低置信度自动放行。

**架构：** 新建 `ModerationHandler` 插入责任链 `@Order(4)`（FilterTitleHandler 之前，审核原始文本），调用 DeepSeek chat API 进行文本审核（title + content），解析返回的 JSON `{violation_type, confidence, reason}`，按置信度三档处理。审核 API 异常时降级放行（不阻塞发布主流程），仅记录警告日志。

**技术栈：** Spring Boot 3.3.5 / Java 17 / DeepSeek API（OpenAI 兼容）/ JDK HttpClient + Gson（与 GetNoteTypeHandler 风格一致）/ Saga 补偿型责任链

---

## 关键设计决策

### 1. 审核位置：@Order(4)，FilterTitleHandler 之前
- **原因**：FilterTitleHandler 会把 title 中的敏感词替换为 `**`，审核必须在过滤前看到原始文本
- **副作用**：需要把 FilterTitleHandler 及之后 7 个 Handler 的 @Order 各 +1

### 2. 仅文本审核（非多模态）
- **原因**：DeepSeek 截至 2025.8 无多模态 API；接入 GPT-4V/阿里云内容安全 API 需要额外密钥和成本
- **范围**：审核 title + content 文本，检测色情/暴力/广告引流/虚假信息
- **图片审核**：作为可选扩展，留 TODO 注释，不阻塞当前实现

### 3. 置信度三档处理
| 置信度 | 处理 | 实现方式 |
|---|---|---|
| > 0.8 | 自动拦截 | 抛 `RuntimeException`，触发 Saga 补偿回滚所有已执行 Handler |
| 0.5 - 0.8 | 标记待人工审核 | NoteDto.moderationStatus = "PENDING_REVIEW"，继续发布流程 |
| < 0.5 | 自动放行 | 正常继续责任链 |

### 4. 审核异常降级
- DeepSeek API 超时/错误/返回格式异常时：**降级放行**，记录 `log.warn`
- **不阻塞发布主流程**（审核是辅助手段，不应让审核服务故障导致发布不可用）

---

## 文件结构

### 新建文件
| 文件 | 职责 |
|---|---|
| `redbook-service-note/src/main/java/com/itcast/handler/impl/ModerationHandler.java` | LLM 内容审核 Handler，@Order(4) |

### 修改文件
| 文件 | 改动 |
|---|---|
| `redbook-service-note/src/main/java/com/itcast/model/dto/NoteDto.java` | 新增 `moderationStatus` 字段（String，默认 null） |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/FilterTitleHandler.java` | @Order(4) → @Order(5) |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/GetNoteTypeHandler.java` | @Order(5) → @Order(6) |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/GetTopicHandler.java` | @Order(6) → @Order(7) |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteHandler.java` | @Order(7) → @Order(8) |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteTopicHandler.java` | @Order(8) → @Order(9) |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/SaveLocationToRedisHandler.java` | @Order(9) → @Order(10) |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteToEsHandler.java` | @Order(10) → @Order(11) |

---

## 任务分解

### 任务 1：NoteDto 添加审核状态字段

**文件：** 修改 `redbook-service-note/src/main/java/com/itcast/model/dto/NoteDto.java`

- [ ] **步骤 1：添加 moderationStatus 字段**

```java
@Data
@EqualsAndHashCode(callSuper = true)
public class NoteDto extends Note {
    private MultipartFile file;
    private List<Integer> topicList;
    /** 内容审核状态：null=未审核/放行，"PENDING_REVIEW"=待人工审核，"BLOCKED"=已拦截 */
    private String moderationStatus;
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn -pl redbook-service/redbook-service-note -am compile -q`
预期：BUILD SUCCESS

---

### 任务 2：创建 ModerationHandler

**文件：** 创建 `redbook-service-note/src/main/java/com/itcast/handler/impl/ModerationHandler.java`

- [ ] **步骤 1：实现 ModerationHandler**

```java
package com.itcast.handler.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.itcast.handler.NoteHandler;
import com.itcast.model.dto.NoteDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM 内容审核 Handler（责任链 @Order(4)，FilterTitleHandler 之前）。
 *
 * 审核 title + content 文本，检测色情/暴力/广告引流/虚假信息。
 * 置信度三档处理：
 *   > 0.8  自动拦截（抛异常触发 Saga 补偿）
 *   0.5-0.8 标记 PENDING_REVIEW（继续发布流程）
 *   < 0.5  自动放行
 *
 * 审核异常时降级放行，不阻塞发布主流程。
 */
@Component
@Order(4)
@Slf4j
public class ModerationHandler extends NoteHandler {

    @Value("${deepseek.api_url}")
    private String apiUrl;

    @Value("${deepseek.api_key}")
    private String apiKey;

    /** 高置信度拦截阈值 */
    private static final double BLOCK_THRESHOLD = 0.8;
    /** 中置信度人工审核阈值 */
    private static final double REVIEW_THRESHOLD = 0.5;

    private final Gson gson = new Gson();

    @Override
    public void handle(NoteDto noteDto) throws IOException, InterruptedException {
        String title = noteDto.getTitle();
        String content = noteDto.getContent();

        // 文本为空则跳过审核
        if ((title == null || title.trim().isEmpty())
                && (content == null || content.trim().isEmpty())) {
            log.debug("笔记标题和内容均为空，跳过审核");
            return;
        }

        try {
            ModerationResult result = moderate(title, content);
            if (result == null) {
                // API 异常或解析失败 → 降级放行
                log.warn("审核服务异常，降级放行 noteId={} title={}", noteDto.getId(), title);
                return;
            }

            log.info("笔记审核结果 noteId={} type={} confidence={} reason={}",
                    noteDto.getId(), result.violationType, result.confidence, result.reason);

            if (result.confidence > BLOCK_THRESHOLD) {
                // 高置信度 → 拦截，抛异常触发 Saga 补偿
                noteDto.setModerationStatus("BLOCKED");
                throw new RuntimeException("内容审核拦截：" + result.reason);
            } else if (result.confidence > REVIEW_THRESHOLD) {
                // 中置信度 → 标记待人工审核，继续发布
                noteDto.setModerationStatus("PENDING_REVIEW");
                log.info("笔记标记待人工审核 noteId={}", noteDto.getId());
            } else {
                // 低置信度 → 放行
                log.info("笔记审核通过 noteId={}", noteDto.getId());
            }
        } catch (RuntimeException e) {
            // 高置信度拦截的异常需要继续抛出，触发补偿
            throw e;
        } catch (Exception e) {
            // 其他异常降级放行
            log.warn("审核过程异常，降级放行 noteId={}", noteDto.getId(), e);
        }
    }

    /**
     * 调用 DeepSeek 审核文本
     * @return ModerationResult；API 异常或解析失败返回 null
     */
    private ModerationResult moderate(String title, String content) {
        String prompt = String.format(
                "你是内容审核助手。请审核以下笔记内容是否违规。只返回 JSON，不要输出其他内容。\n" +
                "违规类型：porn(色情)、violence(暴力)、ad(广告引流)、fake(虚假信息)、none(正常)\n" +
                "返回格式：{\"violation_type\":\"类型\",\"confidence\":0.0到1.0的数字,\"reason\":\"原因\"}\n\n" +
                "标题：%s\n内容：%s",
                title == null ? "" : title,
                content == null ? "" : content
        );

        String llmOutput = callDeepSeek(prompt);
        if (llmOutput == null) {
            return null;
        }

        return parseResult(llmOutput);
    }

    /** 调用 DeepSeek chat API（与 GetNoteTypeHandler 风格一致） */
    private String callDeepSeek(String userMessage) {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String requestBody = String.format(
                    "{ \"model\": \"deepseek-chat\", \"messages\": [ " +
                    "{\"role\": \"system\", \"content\": \"You are a content moderation assistant.\"}, " +
                    "{\"role\": \"user\", \"content\": \"%s\"} " +
                    "], \"temperature\": 0.1 }",
                    userMessage.replace("\"", "\\\"").replace("\n", "\\n")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject resp = gson.fromJson(response.body(), JsonObject.class);
                return resp.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
            } else {
                log.error("DeepSeek 审核请求失败: {} - {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("DeepSeek 审核调用异常", e);
            return null;
        }
    }

    /** 解析 LLM 返回的 JSON 审核 */
    private ModerationResult parseResult(String text) {
        try {
            // 提取 JSON（支持 ```json 块和裸 JSON）
            String json = extractJson(text);
            if (json == null) return null;

            JsonObject obj = gson.fromJson(json, JsonObject.class);
            ModerationResult result = new ModerationResult();
            result.violationType = obj.has("violation_type") ? obj.get("violation_type").getAsString() : "none";
            result.confidence = obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0.0;
            result.reason = obj.has("reason") ? obj.get("reason").getAsString() : "";
            return result;
        } catch (JsonSyntaxException e) {
            log.warn("审核结果解析失败: {}", text);
            return null;
        }
    }

    private String extractJson(String text) {
        // 尝试 ```json 块
        int start = text.indexOf("```json");
        if (start >= 0) {
            int end = text.indexOf("```", start + 7);
            if (end > start) return text.substring(start + 7, end).trim();
        }
        // 尝试裸 ``` 块
        start = text.indexOf("```");
        if (start >= 0) {
            int end = text.indexOf("```", start + 3);
            if (end > start) {
                String candidate = text.substring(start + 3, end).trim();
                if (candidate.startsWith("{")) return candidate;
            }
        }
        // 尝试整段
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
        return null;
    }

    /** 审核结果 DTO */
    private static class ModerationResult {
        String violationType;  // porn/violence/ad/fake/none
        double confidence;     // 0.0 - 1.0
        String reason;
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn -pl redbook-service/redbook-service-note -am compile -q`
预期：BUILD SUCCESS

---

### 任务 3：调整后续 Handler 的 @Order 值

**目的：** 让 ModerationHandler(@Order(4)) 在 FilterTitleHandler 之前执行，审核原始文本。

- [ ] **步骤 1：FilterTitleHandler @Order(4) → @Order(5)**

文件：`redbook-service-note/src/main/java/com/itcast/handler/impl/FilterTitleHandler.java`
修改：`@Order(4)` → `@Order(5)`

- [ ] **步骤 2：GetNoteTypeHandler @Order(5) → @Order(6)**

文件：`redbook-service-note/src/main/java/com/itcast/handler/impl/GetNoteTypeHandler.java`
修改：`@Order(5)` → `@Order(6)`

- [ ] **步骤 3：GetTopicHandler @Order(6) → @Order(7)**

文件：`redbook-service-note/src/main/java/com/itcast/handler/impl/GetTopicHandler.java`
修改：`@Order(6)` → `@Order(7)`

- [ ] **步骤 4：SaveNoteHandler @Order(7) → @Order(8)**

文件：`redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteHandler.java`
修改：`@Order(7)` → `@Order(8)`

- [ ] **步骤 5：SaveNoteTopicHandler @Order(8) → @Order(9)**

文件：`redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteTopicHandler.java`
修改：`@Order(8)` → `@Order(9)`

- [ ] **步骤 6：SaveLocationToRedisHandler @Order(9) → @Order(10)**

文件：`redbook-service-note/src/main/java/com/itcast/handler/impl/SaveLocationToRedisHandler.java`
修改：`@Order(9)` → `@Order(10)`

- [ ] **步骤 7：SaveNoteToEsHandler @Order(10) → @Order(11)**

文件：`redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteToEsHandler.java`
修改：`@Order(10)` → `@Order(11)`

- [ ] **步骤 8：编译验证**

运行：`mvn -pl redbook-service/redbook-service-note -am compile -q`
预期：BUILD SUCCESS

---

### 任务 4：全量编译与责任链顺序验证

- [ ] **步骤 1：全项目编译**

运行：`mvn compile -q`
预期：BUILD SUCCESS（exit code 0）

- [ ] **步骤 2：验证责任链顺序**

确认 11 个 Handler 的 @Order 值：
| Handler | @Order |
|---|---|
| SetUserIdHandler | 1 |
| UploadImgHandler | 2 |
| GetLocationHandler | 3 |
| **ModerationHandler** | **4** |
| FilterTitleHandler | 5 |
| GetNoteTypeHandler | 6 |
| GetTopicHandler | 7 |
| SaveNoteHandler | 8 |
| SaveNoteTopicHandler | 9 |
| SaveLocationToRedisHandler | 10 |
| SaveNoteToEsHandler | 11 |

---

## 自检

### 1. 规格覆盖度
- ✅ 责任链插入 ModerationHandler → 任务 2
- ✅ 检测色情/暴力/广告/虚假信息 → 任务 2 prompt
- ✅ 高置信度自动拦截 → 任务 2 BLOCK_THRESHOLD
- ✅ 中置信度标记人工复审 → 任务 2 REVIEW_THRESHOLD + NoteDto.moderationStatus
- ✅ 低置信度自动放行 → 任务 2 else 分支
- ⚠️ 图片审核 → 未实现（DeepSeek 无多模态），文档已说明

### 2. 占位符扫描
- 无 TODO/待定/占位符
- 所有代码块完整

### 3. 类型一致性
- NoteDto.moderationStatus：String 类型，任务 1 和任务 2 一致
- ModerationResult：内部类，字段名 violationType/confidence/reason 一致
- @Order 值：任务 3 的 7 个文件修改值与任务 4 验证表一致
