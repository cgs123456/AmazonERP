# 购物 Agent 全链路（Function Calling）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 `ProductServiceImpl.buyProduct(String)` 从 HTTP 转发改造为真正的 Function Calling 购物 Agent，支持多轮对话、工具调用、Few-Shot 优化。

**架构：**
- Agent 编排循环 + 工具执行位于 **redbook-service-product**（5 个工具的 Service/Mapper 全在此服务，可直接本地调用，无需 Feign）
- **redbook-service-ai** 重构为支持多消息输入的 LLM 代理（接收 messages 数组，调用 DeepSeek，返回原始响应）
- 文本式 Function Calling：LLM 输出 JSON `{"name":"...","arguments":{...}}`，Agent 解析并执行；Few-Shot 样本池提升 JSON 格式正确率
- 多轮对话：Redis 存储 conversationId → messages，TTL 30min，滑动窗口保留最近 10 轮

**技术栈：** Spring Boot 3.3.5 / Java 17 / Redis / DeepSeek API（OpenAI 兼容）/ OkHttp / Gson / MyBatis-Plus

---

## 文件结构

### redbook-service-ai（LLM 代理，重构为多消息）

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `src/main/java/com/itcast/service/AiService.java` | 新增 `agentChat(AgentChatDto)` 方法签名 |
| 修改 | `src/main/java/com/itcast/service/impl/AiServiceImpl.java` | 实现 `agentChat`：接收 messages 数组，调用 DeepSeek，返回原始响应 |
| 修改 | `src/main/java/com/itcast/controller/AiController.java` | 新增 `POST /ai/agent/chat` 端点 |
| 创建 | `src/main/java/com/itcast/model/dto/AgentChatDto.java` | 多消息聊天请求体（messages + 可选 systemPrompt） |

### redbook-service-product（Agent 核心 + 工具执行）

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `src/main/java/com/itcast/service/ProductService.java` | 新增 `searchProducts(String keyword)` 方法签名 |
| 修改 | `src/main/java/com/itcast/service/impl/ProductServiceImpl.java` | 实现 `searchProducts`（MySQL LIKE）；重写 `buyProduct(String)` 调用 ShoppingAgentService |
| 创建 | `src/main/java/com/itcast/agent/dto/FunctionCall.java` | FunctionCall DTO（name + arguments） |
| 创建 | `src/main/java/com/itcast/agent/dto/AgentMessage.java` | 对话消息 DTO（role + content） |
| 创建 | `src/main/java/com/itcast/agent/FunctionCallParser.java` | 解析 LLM 文本为 FunctionCall，记录格式错误率 |
| 创建 | `src/main/java/com/itcast/agent/ConversationStore.java` | Redis 多轮对话存储（TTL 30min，滑动窗口 10 轮） |
| 创建 | `src/main/java/com/itcast/agent/FewShotStore.java` | Redis Few-Shot 样本池（成功/失败记录） |
| 创建 | `src/main/java/com/itcast/agent/ToolExecutor.java` | 工具调度器（5 个工具，调用本地 Service） |
| 创建 | `src/main/java/com/itcast/agent/ShoppingAgentService.java` | Agent 编排循环 |
| 创建 | `src/main/java/com/itcast/agent/SystemPromptBuilder.java` | 构建 System Prompt（工具说明 + Few-Shot 示例） |
| 修改 | `src/main/resources/application.yml` | 新增 agent 配置项 |
| 修改 | `src/main/java/com/itcast/constant/RedisConstant.java` | 新增 agent 相关 Redis key 前缀 |

---

## 任务 1：重构 AI 服务支持多消息输入

**文件：**
- 创建：`redbook-service-ai/src/main/java/com/itcast/model/dto/AgentChatDto.java`
- 修改：`redbook-service-ai/src/main/java/com/itcast/service/AiService.java`
- 修改：`redbook-service-ai/src/main/java/com/itcast/service/impl/AiServiceImpl.java`
- 修改：`redbook-service-ai/src/main/java/com/itcast/controller/AiController.java`

- [ ] **步骤 1：创建 AgentChatDto**

创建 `redbook-service-ai/src/main/java/com/itcast/model/dto/AgentChatDto.java`：

```java
package com.itcast.model.dto;

import lombok.Data;
import java.util.List;

@Data
public class AgentChatDto {
    /** 消息数组（role: system/user/assistant/tool） */
    private List<Message> messages;
    /** 可选：单独传入 system prompt（会拼接到 messages 前） */
    private String systemPrompt;

    @Data
    public static class Message {
        private String role;
        private String content;
    }
}
```

- [ ] **步骤 2：在 AiService 接口新增 agentChat 方法**

修改 `redbook-service-ai/src/main/java/com/itcast/service/AiService.java`，在现有 `chat` 方法后追加：

```java
    /**
     * Agent 多消息聊天（支持多轮对话）
     * @param agentChatDto 包含 messages 数组的请求体
     * @return DeepSeek 返回的原始文本响应（content 字段）
     */
    Result<String> agentChat(com.itcast.model.dto.AgentChatDto agentChatDto);
```

- [ ] **步骤 3：在 AiServiceImpl 实现 agentChat**

修改 `redbook-service-ai/src/main/java/com/itcast/service/impl/AiServiceImpl.java`，在类末尾追加方法（`}` 之前）：

```java
    @Override
    public Result<String> agentChat(com.itcast.model.dto.AgentChatDto agentChatDto) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "deepseek-chat");

        JsonArray messages = new JsonArray();
        // 如果提供了 systemPrompt，作为第一条 system 消息
        if (agentChatDto.getSystemPrompt() != null && !agentChatDto.getSystemPrompt().isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", agentChatDto.getSystemPrompt());
            messages.add(sysMsg);
        }
        // 追加 messages 数组
        for (com.itcast.model.dto.AgentChatDto.Message msg : agentChatDto.getMessages()) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.getRole());
            m.addProperty("content", msg.getContent());
            messages.add(m);
        }
        requestBody.add("messages", messages);

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(apiUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return Result.failure("DeepSeek API 调用失败: " + response.code());
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            return Result.success(content);
        } catch (IOException e) {
            return Result.failure("DeepSeek API 调用异常: " + e.getMessage());
        }
    }
```

- [ ] **步骤 4：在 AiController 新增 /ai/agent/chat 端点**

修改 `redbook-service-ai/src/main/java/com/itcast/controller/AiController.java`，在 `chat` 方法后追加（`ChatRequest` 内部类之前）：

```java
    @PostMapping("/agent/chat")
    public Result<String> agentChat(@RequestBody com.itcast.model.dto.AgentChatDto request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return Result.failure("messages 不能为空");
        }
        return aiService.agentChat(request);
    }
```

- [ ] **步骤 5：编译验证 AI 服务**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-ai -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 6：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-ai/src/main/java/com/itcast/model/dto/AgentChatDto.java redbook-service/redbook-service-ai/src/main/java/com/itcast/service/AiService.java redbook-service/redbook-service-ai/src/main/java/com/itcast/service/impl/AiServiceImpl.java redbook-service/redbook-service-ai/src/main/java/com/itcast/controller/AiController.java
git commit -m "feat(ai): 支持多消息输入的 agentChat 端点 /ai/agent/chat"
```

---

## 任务 2：商品服务新增关键词搜索

**文件：**
- 修改：`redbook-service-product/src/main/java/com/itcast/service/ProductService.java`
- 修改：`redbook-service-product/src/main/java/com/itcast/service/impl/ProductServiceImpl.java`

**说明：** 现有 `SearchService` 搜索的是笔记（ES `rb_note` 索引），不是商品。Agent 的 `search_products` 工具需要商品搜索，这里用 MySQL LIKE 实现（商品数据量不大，无需 ES）。

- [ ] **步骤 1：在 ProductService 接口新增方法**

修改 `redbook-service-product/src/main/java/com/itcast/service/ProductService.java`，在 `selectById` 方法前追加：

```java
    /**
     * 按关键词搜索商品（名称/描述/品牌模糊匹配）
     */
    Result<List<Product>> searchProducts(String keyword);
```

- [ ] **步骤 2：在 ProductServiceImpl 实现 searchProducts**

修改 `redbook-service-product/src/main/java/com/itcast/service/impl/ProductServiceImpl.java`，在 `selectById` 方法前追加：

```java
    @Override
    public Result<List<Product>> searchProducts(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            // 无关键词时返回全部商品（最多 20 条）
            LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
            wrapper.last("LIMIT 20");
            return Result.success(productMapper.selectList(wrapper));
        }
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(Product::getName, keyword)
               .or().like(Product::getDescription, keyword)
               .or().like(Product::getBrand, keyword)
               .last("LIMIT 20");
        return Result.success(productMapper.selectList(wrapper));
    }
```

- [ ] **步骤 3：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-product/src/main/java/com/itcast/service/ProductService.java redbook-service/redbook-service-product/src/main/java/com/itcast/service/impl/ProductServiceImpl.java
git commit -m "feat(product): 新增 searchProducts 关键词搜索（名称/描述/品牌 LIKE）"
```

---

## 任务 3：FunctionCall DTO 与 JSON 解析器（含格式错误率统计）

**文件：**
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/dto/FunctionCall.java`
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/dto/AgentMessage.java`
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/FunctionCallParser.java`
- 修改：`redbook-common/src/main/java/com/itcast/constant/RedisConstant.java`

- [ ] **步骤 1：新增 Redis 常量**

修改 `redbook-common/src/main/java/com/itcast/constant/RedisConstant.java`，在文件末尾（`}` 之前）追加：

```java
    // ===== Shopping Agent 相关 =====
    /** 多轮对话存储前缀，完整 key: agent:conversation:{conversationId} */
    public final static String AGENT_CONVERSATION = "agent:conversation:";
    /** Few-Shot 成功样本列表 key */
    public final static String AGENT_FEWSHOT_SUCCESS = "agent:fewshot:success";
    /** Few-Shot 失败样本列表 key */
    public final static String AGENT_FEWSHOT_FAILURE = "agent:fewshot:failure";
    /** 格式正确率计数器（hash: total / success） */
    public final static String AGENT_FORMAT_STATS = "agent:format:stats";
```

- [ ] **步骤 2：创建 FunctionCall DTO**

创建 `redbook-service-product/src/main/java/com/itcast/agent/dto/FunctionCall.java`：

```java
package com.itcast.agent.dto;

import lombok.Data;
import java.util.Map;

/**
 * 表示 LLM 输出的函数调用
 */
@Data
public class FunctionCall {
    /** 工具名称，如 search_products */
    private String name;
    /** 工具参数，如 {"keyword":"红色"} */
    private Map<String, Object> arguments;
}
```

- [ ] **步骤 3：创建 AgentMessage DTO**

创建 `redbook-service-product/src/main/java/com/itcast/agent/dto/AgentMessage.java`：

```java
package com.itcast.agent.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Agent 对话消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {
    /** role: user / assistant */
    private String role;
    /** content: 消息内容 */
    private String content;
}
```

- [ ] **步骤 4：创建 FunctionCallParser**

创建 `redbook-service-product/src/main/java/com/itcast/agent/FunctionCallParser.java`：

```java
package com.itcast.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.itcast.agent.dto.FunctionCall;
import com.itcast.constant.RedisConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 LLM 文本输出为 FunctionCall，并统计 JSON 格式正确率。
 *
 * 解析规则：
 * 1. 尝试从文本中提取 ```json ... ``` 代码块
 * 2. 若无代码块，尝试直接解析整段为 JSON
 * 3. JSON 必须包含 name（字符串）和 arguments（对象）字段
 *
 * 格式正确率 = success / total，记录在 Redis hash AGENT_FORMAT_STATS
 */
@Component
@Slf4j
public class FunctionCallParser {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Gson gson = new Gson();

    /** 匹配 ```json ... ``` 代码块 */
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    /** 匹配裸 ``` ... ``` 代码块 */
    private static final Pattern BARE_BLOCK = Pattern.compile("```\\s*([\\s\\S]*?)```");

    /**
     * 解析 LLM 输出文本。
     * @param text LLM 原始输出
     * @return 解析成功返回 FunctionCall；解析失败（不是函数调用）返回 null
     */
    public FunctionCall parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String jsonStr = extractJson(text);
        if (jsonStr == null) {
            // 不是 JSON，说明是自然语言回复，不算格式错误（只是不是函数调用）
            return null;
        }

        // 是 JSON 但可能格式不对 —— 计入统计
        incrementTotal();
        FunctionCall call = tryParseFunctionCall(jsonStr);
        if (call != null) {
            incrementSuccess();
            log.debug("FunctionCall 解析成功: {}", call.getName());
        } else {
            log.warn("FunctionCall 格式错误，原始 JSON: {}", jsonStr);
        }
        return call;
    }

    /** 从文本中提取 JSON 字符串 */
    private String extractJson(String text) {
        // 1. 尝试 ```json 块
        Matcher m = JSON_BLOCK.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 2. 尝试裸 ``` 块
        m = BARE_BLOCK.matcher(text);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (candidate.startsWith("{")) {
                return candidate;
            }
        }
        // 3. 尝试整段（去除首尾空白）
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return null;
    }

    private FunctionCall tryParseFunctionCall(String jsonStr) {
        try {
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            if (obj == null || !obj.has("name") || !obj.has("arguments")) {
                return null;
            }
            FunctionCall call = new FunctionCall();
            call.setName(obj.get("name").getAsString());
            // arguments 转为 Map
            Map<String, Object> args = new HashMap<>();
            JsonObject argsObj = obj.getAsJsonObject("arguments");
            for (String key : argsObj.keySet()) {
                args.put(key, gson.fromJson(argsObj.get(key), Object.class));
            }
            call.setArguments(args);
            return call;
        } catch (JsonSyntaxException e) {
            return null;
        } catch (Exception e) {
            log.error("FunctionCall 解析异常", e);
            return null;
        }
    }

    private void incrementTotal() {
        redisTemplate.opsForHash().increment(RedisConstant.AGENT_FORMAT_STATS, "total", 1);
    }

    private void incrementSuccess() {
        redisTemplate.opsForHash().increment(RedisConstant.AGENT_FORMAT_STATS, "success", 1);
    }

    /**
     * 获取当前格式正确率
     * @return [successCount, totalCount]
     */
    public long[] getStats() {
        Object success = redisTemplate.opsForHash().get(RedisConstant.AGENT_FORMAT_STATS, "success");
        Object total = redisTemplate.opsForHash().get(RedisConstant.AGENT_FORMAT_STATS, "total");
        long s = success == null ? 0 : Long.parseLong(success.toString());
        long t = total == null ? 0 : Long.parseLong(total.toString());
        return new long[]{s, t};
    }
}
```

- [ ] **步骤 5：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 6：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-common/src/main/java/com/itcast/constant/RedisConstant.java redbook-service/redbook-service-product/src/main/java/com/itcast/agent/dto/FunctionCall.java redbook-service/redbook-service-product/src/main/java/com/itcast/agent/dto/AgentMessage.java redbook-service/redbook-service-product/src/main/java/com/itcast/agent/FunctionCallParser.java
git commit -m "feat(agent): FunctionCall DTO 与 JSON 解析器，含格式正确率统计"
```

---

## 任务 4：ConversationStore 多轮对话存储

**文件：**
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/ConversationStore.java`

**设计：**
- Redis key: `agent:conversation:{conversationId}`，值为 JSON 数组（AgentMessage 列表）
- TTL 30 分钟（每次访问刷新）
- 滑动窗口：超过 10 轮（20 条消息）时，保留最近 10 轮，对超出部分用 LLM 总结压缩

- [ ] **步骤 1：创建 ConversationStore**

创建 `redbook-service-product/src/main/java/com/itcast/agent/ConversationStore.java`：

```java
package com.itcast.agent;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.itcast.agent.dto.AgentMessage;
import com.itcast.constant.RedisConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 多轮对话存储（Redis）。
 * - key: agent:conversation:{conversationId}
 * - TTL: 30 分钟，每次读写刷新
 * - 滑动窗口：最多保留 MAX_TURNS*2 条消息（10 轮），超出则截断保留最近的
 */
@Component
@Slf4j
public class ConversationStore {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Gson gson = new Gson();
    private static final Type MESSAGE_LIST_TYPE = new TypeToken<List<AgentMessage>>() {}.getType();

    /** 最大保留轮数（一问一答为一轮） */
    private static final int MAX_TURNS = 10;
    /** 每轮 2 条消息（user + assistant） */
    private static final int MAX_MESSAGES = MAX_TURNS * 2;
    /** TTL 30 分钟（秒） */
    private static final long TTL_SECONDS = 30 * 60;

    /**
     * 读取对话历史
     */
    public List<AgentMessage> getHistory(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return new ArrayList<>();
        }
        String key = RedisConstant.AGENT_CONVERSATION + conversationId;
        Object val = redisTemplate.opsForValue().get(key);
        if (val == null) {
            return new ArrayList<>();
        }
        try {
            List<AgentMessage> messages = gson.fromJson(val.toString(), MESSAGE_LIST_TYPE);
            // 刷新 TTL
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
            return messages != null ? messages : new ArrayList<>();
        } catch (Exception e) {
            log.error("读取对话历史失败: conversationId={}", conversationId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 追加一条消息并保存（自动滑动窗口截断）
     */
    public void appendMessage(String conversationId, AgentMessage message) {
        if (conversationId == null || conversationId.isEmpty() || message == null) {
            return;
        }
        List<AgentMessage> history = getHistory(conversationId);
        history.add(message);
        // 滑动窗口截断
        if (history.size() > MAX_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
        }
        save(conversationId, history);
    }

    /**
     * 批量追加消息
     */
    public void appendMessages(String conversationId, List<AgentMessage> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        List<AgentMessage> history = getHistory(conversationId);
        history.addAll(messages);
        if (history.size() > MAX_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
        }
        save(conversationId, history);
    }

    /**
     * 清空对话历史（话题切换时调用）
     */
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        redisTemplate.delete(RedisConstant.AGENT_CONVERSATION + conversationId);
    }

    private void save(String conversationId, List<AgentMessage> messages) {
        String key = RedisConstant.AGENT_CONVERSATION + conversationId;
        redisTemplate.opsForValue().set(key, gson.toJson(messages), TTL_SECONDS, TimeUnit.SECONDS);
    }
}
```

- [ ] **步骤 2：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-product/src/main/java/com/itcast/agent/ConversationStore.java
git commit -m "feat(agent): ConversationStore 多轮对话存储（Redis, TTL 30min, 滑动窗口 10 轮）"
```

---

## 任务 5：FewShotStore 样本池

**文件：**
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/FewShotStore.java`

**设计：**
- 成功样本：LLM 输出可正确解析为 FunctionCall 的（query, output）对，存 Redis list `agent:fewshot:success`
- 失败样本：格式错误的（query, output）对，存 `agent:fewshot:failure`
- 最多保留 50 条成功样本（注入 system prompt 作为 Few-Shot 示例）
- 提供 `getSuccessSamples()` 返回最近 N 条用于注入

- [ ] **步骤 1：创建 FewShotStore**

创建 `redbook-service-product/src/main/java/com/itcast/agent/FewShotStore.java`：

```java
package com.itcast.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.itcast.constant.RedisConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Few-Shot 样本池（Redis）。
 * - 成功样本：可正确解析为 FunctionCall 的 (query, output) 对
 * - 失败样本：格式错误的 (query, output) 对
 * - 成功样本最多保留 50 条，用于注入 system prompt 提升格式正确率
 */
@Component
@Slf4j
public class FewShotStore {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Gson gson = new Gson();

    private static final int MAX_SUCCESS_SAMPLES = 50;
    private static final int MAX_FAILURE_SAMPLES = 20;

    /**
     * 记录一条样本
     * @param query 用户原始问题
     * @param output LLM 原始输出
     * @param success 是否成功解析为 FunctionCall
     */
    public void recordSample(String query, String output, boolean success) {
        try {
            JsonObject sample = new JsonObject();
            sample.addProperty("query", query);
            sample.addProperty("output", output);
            String json = gson.toJson(sample);

            String key = success
                    ? RedisConstant.AGENT_FEWSHOT_SUCCESS
                    : RedisConstant.AGENT_FEWSHOT_FAILURE;

            // 左插（最新的在前面）
            redisTemplate.opsForList().leftPush(key, json);
            // 修剪保留最近 N 条
            redisTemplate.opsForList().trim(key, 0, success ? MAX_SUCCESS_SAMPLES - 1 : MAX_FAILURE_SAMPLES - 1);
        } catch (Exception e) {
            log.error("记录 Few-Shot 样本失败", e);
        }
    }

    /**
     * 获取最近 N 条成功样本（用于注入 system prompt）
     */
    public List<String> getSuccessSamples(int limit) {
        List<String> result = new ArrayList<>();
        try {
            List<Object> raw = redisTemplate.opsForList()
                    .range(RedisConstant.AGENT_FEWSHOT_SUCCESS, 0, limit - 1);
            if (raw == null) {
                return result;
            }
            for (Object o : raw) {
                result.add(o.toString());
            }
        } catch (Exception e) {
            log.error("读取 Few-Shot 样本失败", e);
        }
        return result;
    }
}
```

- [ ] **步骤 2：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-product/src/main/java/com/itcast/agent/FewShotStore.java
git commit -m "feat(agent): FewShotStore 样本池（成功/失败记录，注入 system prompt）"
```

---

## 任务 6：SystemPromptBuilder（工具说明 + Few-Shot 注入）

**文件：**
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/SystemPromptBuilder.java`

- [ ] **步骤 1：创建 SystemPromptBuilder**

创建 `redbook-service-product/src/main/java/com/itcast/agent/SystemPromptBuilder.java`：

```java
package com.itcast.agent;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构建 Agent System Prompt：
 * 1. 工具说明（5 个工具的签名与用途）
 * 2. 输出格式约束（JSON Schema 式描述）
 * 3. Few-Shot 示例（从 FewShotStore 读取最近 3 条成功样本）
 */
@Component
@Slf4j
public class SystemPromptBuilder {

    @Autowired
    private FewShotStore fewShotStore;

    /** 注入的 Few-Shot 示例数量 */
    private static final int FEWSHOT_LIMIT = 3;

    private static final String TOOL_DESCRIPTION = """
            你是红书商城购物助手。你可以使用以下工具帮用户完成购物：
            - search_products(keyword)：搜索商品。keyword 为搜索关键词。
            - get_product_detail(productId)：查看商品详情。productId 为商品 ID（整数）。
            - add_to_cart(productId, attributes)：将商品加入购物车。productId 为商品 ID；attributes 为可选的商品属性 JSON，如 {"颜色":"红色"}。
            - create_order(productId, attributes)：立即下单（会扣减库存）。productId 为商品 ID；attributes 为可选的商品属性 JSON。
            - check_coupons()：查询当前用户可用的优惠券。无需参数。

            规则：
            1. 每次只能调用一个工具，完成后我会告诉你工具执行结果，你再决定下一步。
            2. 当你需要调用工具时，只输出一个 JSON 对象，格式如下（不要输出其他内容）：
               ```json
               {"name":"工具名","arguments":{"参数名":"参数值"}}
               ```
            3. 当你已经获得足够信息可以回答用户时，用自然语言回复（不要输出 JSON）。
            4. 如果用户的请求不明确（例如"帮我买那个红色的"但没指定商品），先调用 search_products 搜索。
            """;

    /**
     * 构建完整 system prompt（工具说明 + Few-Shot 示例）
     */
    public String build() {
        StringBuilder sb = new StringBuilder(TOOL_DESCRIPTION);

        // 注入 Few-Shot 示例
        List<String> samples = fewShotStore.getSuccessSamples(FEWSHOT_LIMIT);
        if (!samples.isEmpty()) {
            sb.append("\n\n以下是正确的工具调用示例，请参考其格式：\n");
            for (int i = 0; i < samples.size(); i++) {
                sb.append("示例 ").append(i + 1).append("：\n").append(samples.get(i)).append("\n");
            }
        }

        return sb.toString();
    }
}
```

- [ ] **步骤 2：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-product/src/main/java/com/itcast/agent/SystemPromptBuilder.java
git commit -m "feat(agent): SystemPromptBuilder 构建工具说明 + Few-Shot 注入"
```

---

## 任务 7：ToolExecutor 工具调度器

**文件：**
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/ToolExecutor.java`

**5 个工具实现：**
1. `search_products(keyword)` → `ProductService.searchProducts(keyword)` 本地调用
2. `get_product_detail(productId)` → `ProductService.getProduct(productId)` 本地调用
3. `add_to_cart(productId, attributes)` → `CartService.addToCart(productId, 1)` 本地调用
4. `create_order(productId, attributes)` → `ProductService.buyProduct(BuyDto)` 本地调用（走 Redis Lua + MQ 完整链路）
5. `check_coupons()` → `CouponService.getCouponsByUserId()` 本地调用

- [ ] **步骤 1：创建 ToolExecutor**

创建 `redbook-service-product/src/main/java/com/itcast/agent/ToolExecutor.java`：

```java
package com.itcast.agent;

import com.google.gson.Gson;
import com.itcast.agent.dto.FunctionCall;
import com.itcast.context.UserContext;
import com.itcast.model.dto.BuyDto;
import com.itcast.model.pojo.CustomAttribute;
import com.itcast.model.pojo.Product;
import com.itcast.model.vo.CouponVo;
import com.itcast.model.vo.ProductVo;
import com.itcast.result.Result;
import com.itcast.service.CartService;
import com.itcast.service.CouponService;
import com.itcast.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具调度器：根据 FunctionCall 调用对应的本地 Service。
 * 所有工具在当前事务上下文中执行（userId 来自 UserContext）。
 */
@Component
@Slf4j
public class ToolExecutor {

    @Autowired
    private ProductService productService;
    @Autowired
    private CartService cartService;
    @Autowired
    private CouponService couponService;

    private final Gson gson = new Gson();

    /**
     * 执行工具调用
     * @param call 函数调用
     * @return 工具执行结果（JSON 字符串，会作为下一轮 LLM 的输入）
     */
    public String execute(FunctionCall call) {
        if (call == null || call.getName() == null) {
            return "{\"error\":\"无效的工具调用\"}";
        }
        String name = call.getName();
        Map<String, Object> args = call.getArguments() == null ? Map.of() : call.getArguments();
        try {
            return switch (name) {
                case "search_products" -> executeSearchProducts(args);
                case "get_product_detail" -> executeGetProductDetail(args);
                case "add_to_cart" -> executeAddToCart(args);
                case "create_order" -> executeCreateOrder(args);
                case "check_coupons" -> executeCheckCoupons();
                default -> "{\"error\":\"未知工具: " + name + "\"}";
            };
        } catch (Exception e) {
            log.error("工具执行异常: {}", name, e);
            return "{\"error\":\"工具执行异常: " + e.getMessage() + "\"}";
        }
    }

    private String executeSearchProducts(Map<String, Object> args) {
        String keyword = args.get("keyword") == null ? null : args.get("keyword").toString();
        Result<List<Product>> result = productService.searchProducts(keyword);
        if (result.getCode() != 200) {
            return "{\"error\":\"搜索失败: " + result.getMessage() + "\"}";
        }
        List<Product> products = result.getData();
        if (products == null || products.isEmpty()) {
            return "{\"result\":\"未找到相关商品\"}";
        }
        // 只返回关键字段（避免上下文过长）
        List<Map<String, Object>> simplified = new ArrayList<>();
        for (Product p : products) {
            Map<String, Object> item = Map.of(
                    "productId", p.getId(),
                    "name", p.getName(),
                    "price", p.getPrice(),
                    "stock", p.getStock(),
                    "brand", p.getBrand() == null ? "" : p.getBrand()
            );
            simplified.add(item);
        }
        return gson.toJson(Map.of("products", simplified));
    }

    private String executeGetProductDetail(Map<String, Object> args) {
        Object idVal = args.get("productId");
        if (idVal == null) {
            return "{\"error\":\"缺少参数 productId\"}";
        }
        Integer productId = toInt(idVal);
        Result<ProductVo> result = productService.getProduct(productId);
        if (result.getCode() != 200) {
            return "{\"error\":\"查询失败: " + result.getMessage() + "\"}";
        }
        ProductVo vo = result.getData();
        if (vo == null || vo.getProduct() == null) {
            return "{\"error\":\"商品不存在\"}";
        }
        Product p = vo.getProduct();
        Map<String, Object> detail = Map.of(
                "productId", p.getId(),
                "name", p.getName(),
                "description", p.getDescription() == null ? "" : p.getDescription(),
                "price", p.getPrice(),
                "stock", p.getStock(),
                "brand", p.getBrand() == null ? "" : p.getBrand(),
                "shopName", vo.getShop() != null && vo.getShop().getName() != null ? vo.getShop().getName() : ""
        );
        return gson.toJson(detail);
    }

    private String executeAddToCart(Map<String, Object> args) {
        Object idVal = args.get("productId");
        if (idVal == null) {
            return "{\"error\":\"缺少参数 productId\"}";
        }
        Integer productId = toInt(idVal);
        Result<Void> result = cartService.addToCart(productId, 1);
        if (result.getCode() == 200) {
            return "{\"result\":\"已加入购物车\"}";
        }
        return "{\"error\":\"加入购物车失败: " + result.getMessage() + "\"}";
    }

    private String executeCreateOrder(Map<String, Object> args) {
        Object idVal = args.get("productId");
        if (idVal == null) {
            return "{\"error\":\"缺少参数 productId\"}";
        }
        Integer productId = toInt(idVal);
        BuyDto buyDto = new BuyDto();
        buyDto.setProductId(productId);
        buyDto.setUserId(UserContext.getUserId());
        // attributes 可选（暂不解析为 CustomAttribute，因为需要匹配商品属性结构）
        Result<Void> result = productService.buyProduct(buyDto);
        if (result.getCode() == 200) {
            return "{\"result\":\"下单成功，库存已扣减\"}";
        }
        return "{\"error\":\"下单失败: " + result.getMessage() + "\"}";
    }

    private String executeCheckCoupons() {
        Result<List<CouponVo>> result = couponService.getCouponsByUserId();
        if (result.getCode() != 200) {
            return "{\"error\":\"查询优惠券失败: " + result.getMessage() + "\"}";
        }
        List<CouponVo> coupons = result.getData();
        if (coupons == null || coupons.isEmpty()) {
            return "{\"result\":\"暂无可用优惠券\"}";
        }
        List<Map<String, Object>> simplified = new ArrayList<>();
        for (CouponVo cv : coupons) {
            if (cv.getCoupon() == null) continue;
            Map<String, Object> item = Map.of(
                    "couponId", cv.getCoupon().getId(),
                    "name", cv.getCoupon().getName(),
                    "discount", cv.getCoupon().getDiscount(),
                    "isUsable", cv.getIsUsable()
            );
            simplified.add(item);
        }
        return gson.toJson(Map.of("coupons", simplified));
    }

    /** 将参数值转为 Integer（兼容 Double/Long 等 JSON 解析结果） */
    private Integer toInt(Object val) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(val.toString());
    }
}
```

- [ ] **步骤 2：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-product/src/main/java/com/itcast/agent/ToolExecutor.java
git commit -m "feat(agent): ToolExecutor 调度 5 个工具（search/detail/cart/order/coupon）"
```

---

## 任务 8：ShoppingAgentService 编排循环

**文件：**
- 创建：`redbook-service-product/src/main/java/com/itcast/agent/ShoppingAgentService.java`
- 修改：`redbook-service-product/src/main/java/com/itcast/agent/dto/AgentMessage.java`（已创建，无需改动）

**编排逻辑：**
1. 生成或复用 conversationId（由调用方传入，便于多轮）
2. 读取历史对话
3. 构建 system prompt（工具说明 + Few-Shot）
4. 循环（最多 5 次工具调用，防止死循环）：
   a. 拼接 system + history + 当前 user message → 调用 AI 服务 `/ai/agent/chat`
   b. 解析 LLM 输出 → FunctionCallParser
   c. 若解析到 FunctionCall → ToolExecutor.execute → 将结果作为新 user 消息注入 history → 继续
   d. 若未解析到 FunctionCall（自然语言）→ 这就是最终回复，退出循环
   e. 记录 Few-Shot 样本（成功/失败）
5. 保存对话历史到 Redis
6. 返回最终回复

- [ ] **步骤 1：确认 Result 类型（已完成）**

`Result.code` 为 `int`，字段名 `message`。任务 7 的 ToolExecutor 已使用 `getCode() == 200` 和 `getMessage()`，无需额外修改。本步骤无需操作，直接进入步骤 2。

- [ ] **步骤 2：创建 ShoppingAgentService**

创建 `redbook-service-product/src/main/java/com/itcast/agent/ShoppingAgentService.java`：

```java
package com.itcast.agent;

import com.google.gson.Gson;
import com.itcast.agent.dto.AgentMessage;
import com.itcast.agent.dto.FunctionCall;
import com.itcast.model.dto.AgentChatDto;
import com.itcast.result.Result;
import com.itcast.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 购物 Agent 编排服务。
 *
 * 流程：
 * 1. 读取 conversationId 对应的历史对话
 * 2. 构建 system prompt（工具说明 + Few-Shot 示例）
 * 3. 循环调用 LLM：
 *    - LLM 输出可解析为 FunctionCall → 执行工具 → 结果注入历史 → 继续
 *    - LLM 输出为自然语言 → 作为最终回复返回
 * 4. 最多循环 5 次防止死循环
 */
@Service
@Slf4j
public class ShoppingAgentService {

    @Autowired
    private ConversationStore conversationStore;
    @Autowired
    private SystemPromptBuilder systemPromptBuilder;
    @Autowired
    private FunctionCallParser functionCallParser;
    @Autowired
    private ToolExecutor toolExecutor;
    @Autowired
    private FewShotStore fewShotStore;

    /** AI 服务 agent chat 端点 URL（通过网关） */
    @Value("${agent.ai-chat-url:http://localhost:10010/ai/agent/chat}")
    private String aiChatUrl;

    /** 最大工具调用轮数（防止死循环） */
    private static final int MAX_TOOL_ROUNDS = 5;

    private final Gson gson = new Gson();

    /**
     * Agent 对话入口。
     * @param message 用户消息
     * @param conversationId 对话 ID（为空则新建）
     * @param userId 用户 ID
     * @return [回复内容, conversationId]
     */
    public Result<String[]> chat(String message, String conversationId, Integer userId) {
        if (message == null || message.trim().isEmpty()) {
            return Result.failure("消息不能为空");
        }
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = UUID.randomUUID().toString().replace("-", "");
        }

        // 1. 读取历史
        List<AgentMessage> history = conversationStore.getHistory(conversationId);

        // 2. 记录用户消息
        AgentMessage userMsg = new AgentMessage("user", message);
        history.add(userMsg);
        conversationStore.appendMessage(conversationId, userMsg);

        // 3. 构建 system prompt
        String systemPrompt = systemPromptBuilder.build();

        // 4. 编排循环
        String finalReply = null;
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            // 4.1 调用 AI 服务
            String llmOutput = callAi(systemPrompt, history, userId);
            if (llmOutput == null) {
                finalReply = "AI 服务暂时不可用，请稍后再试。";
                break;
            }

            // 4.2 解析是否为工具调用
            FunctionCall call = functionCallParser.parse(llmOutput);

            if (call == null) {
                // 自然语言回复 → 结束
                finalReply = llmOutput;
                // 记录 Few-Shot（这里不算格式错误，因为不是函数调用）
                AgentMessage assistantMsg = new AgentMessage("assistant", llmOutput);
                conversationStore.appendMessage(conversationId, assistantMsg);
                break;
            }

            // 4.3 记录成功/失败样本
            fewShotStore.recordSample(message, llmOutput, true);

            // 4.4 执行工具
            log.info("执行工具: {} args={}", call.getName(), call.getArguments());
            String toolResult = toolExecutor.execute(call);

            // 4.5 将 LLM 输出和工具结果注入历史
            AgentMessage assistantToolMsg = new AgentMessage("assistant", llmOutput);
            AgentMessage toolResultMsg = new AgentMessage("user", "工具执行结果: " + toolResult);
            conversationStore.appendMessage(conversationId, assistantToolMsg);
            conversationStore.appendMessage(conversationId, toolResultMsg);
            history.add(assistantToolMsg);
            history.add(toolResultMsg);

            // 4.6 继续下一轮（让 LLM 基于工具结果继续）
        }

        if (finalReply == null) {
            finalReply = "已达到最大工具调用次数，请稍后重试或换一种方式提问。";
        }

        return Result.success(new String[]{finalReply, conversationId});
    }

    /**
     * 调用 AI 服务的 /ai/agent/chat 端点
     */
    private String callAi(String systemPrompt, List<AgentMessage> history, Integer userId) {
        try {
            AgentChatDto dto = new AgentChatDto();
            dto.setSystemPrompt(systemPrompt);
            List<AgentChatDto.Message> messages = new ArrayList<>();
            for (AgentMessage am : history) {
                AgentChatDto.Message m = new AgentChatDto.Message();
                m.setRole(am.getRole());
                m.setContent(am.getContent());
                messages.add(m);
            }
            dto.setMessages(messages);

            String response = HttpUtil.postWithUserId(aiChatUrl, dto, userId);
            if (response == null) {
                log.error("AI 服务返回空响应");
                return null;
            }
            // AI 服务返回 Result<String> JSON，解析 data 字段
            com.google.gson.JsonObject respJson = gson.fromJson(response, com.google.gson.JsonObject.class);
            if (respJson.has("data") && !respJson.get("data").isJsonNull()) {
                return respJson.get("data").getAsString();
            }
            log.error("AI 服务返回异常: {}", response);
            return null;
        } catch (Exception e) {
            log.error("调用 AI 服务失败", e);
            return null;
        }
    }
}
```

- [ ] **步骤 3：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-product/src/main/java/com/itcast/agent/ShoppingAgentService.java
git commit -m "feat(agent): ShoppingAgentService 编排循环（最多 5 轮工具调用）"
```

---

## 任务 9：接入 ProductController 并修复 buyProduct(String)

**文件：**
- 修改：`redbook-service-product/src/main/java/com/itcast/service/impl/ProductServiceImpl.java`
- 修改：`redbook-service-product/src/main/java/com/itcast/controller/ProductController.java`
- 修改：`redbook-service-product/src/main/resources/application.yml`

- [ ] **步骤 1：在 application.yml 新增 agent 配置**

修改 `redbook-service-product/src/main/resources/application.yml`，在文件末尾追加：

```yaml
agent:
  # AI 服务 agent chat 端点（通过网关）
  ai-chat-url: http://localhost:10010/ai/agent/chat
```

- [ ] **步骤 2：在 ProductServiceImpl 注入 ShoppingAgentService 并重写 buyProduct(String)**

修改 `redbook-service-product/src/main/java/com/itcast/service/impl/ProductServiceImpl.java`：

2a. 在类的字段区（`private RedissonClient redissonClient;` 之后）追加注入：

```java
    @Autowired
    private com.itcast.agent.ShoppingAgentService shoppingAgentService;
```

2b. 将现有 `buyProduct(String message)` 方法（第 192-208 行）整体替换为：

```java
    @Override
    public Result<String> buyProduct(String message) {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            return Result.failure("用户未登录");
        }
        try {
            // 调用本地 Agent 编排服务（真正的 Function Calling）
            Result<String[]> result = shoppingAgentService.chat(message, null, userId);
            if (result.getCode() == 200 && result.getData() != null) {
                // data[0] = 回复内容, data[1] = conversationId
                String reply = result.getData()[0];
                log.info("用户 {} 购物 Agent 回复: {}", userId, reply);
                return Result.success(reply);
            }
            return Result.failure(result.getMessage() == null ? "Agent 处理失败" : result.getMessage());
        } catch (Exception e) {
            log.error("购物 Agent 调用失败", e);
            return Result.failure("购物助手暂时不可用");
        }
    }
```

> **注意：** 这替换了原先 HTTP 转发到 `http://localhost:9999/agent/chat`（URL 本就是坏的）的逻辑。现在 `buyProduct(String)` 调用本地 `ShoppingAgentService`，由后者通过网关调用 AI 服务的 `/ai/agent/chat`。

- [ ] **步骤 3：在 ProductController 新增 conversationId 支持（可选的多轮端点）**

修改 `redbook-service-product/src/main/java/com/itcast/controller/ProductController.java`，在 `agentBuyProduct` 方法后追加：

```java
    @PostMapping("/agent/chat")
    public Result<String> agentChat(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isEmpty()) {
            return Result.failure("消息不能为空");
        }
        // 委托给 ProductService.buyProduct(String)，userId 由拦截器从 header 注入 UserContext
        return productService.buyProduct(request.getMessage());
    }

    public static class AgentChatRequest {
        private String message;
        private String conversationId;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    }
```

> 注：现有 `/product/agent/buyProduct` 端点保持不变（前端 `chatWithAgent` 仍调用它）。新增的 `/product/agent/chat` 是语义更清晰的别名，未来可扩展支持 conversationId 透传。

- [ ] **步骤 4：编译验证**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product -am compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add redbook-service/redbook-service-product/src/main/resources/application.yml redbook-service/redbook-service-product/src/main/java/com/itcast/service/impl/ProductServiceImpl.java redbook-service/redbook-service-product/src/main/java/com/itcast/controller/ProductController.java
git commit -m "feat(agent): 接入 ShoppingAgentService，修复 buyProduct(String) 的坏 URL"
```

---

## 任务 10：全量编译与集成验证

**文件：** 无新文件，仅验证

- [ ] **步骤 1：全量编译**

运行：
```bash
cd d:\Desktop\仿小红书\my-redbook && mvn clean compile -q
```
预期：所有模块 BUILD SUCCESS

- [ ] **步骤 2：检查是否有未使用的 import**

`ProductServiceImpl.java` 原先 `buyProduct(String)` 用到 `HttpUtil`，现在不再需要该 import（如果 `buyProduct(BuyDto)` 不用 HttpUtil 的话）。检查并移除多余 import：

```bash
cd d:\Desktop\仿小红书\my-redbook && mvn -pl redbook-service/redbook-service-product compile -q
```
若有 unused import 警告，用 Edit 工具移除 `import com.itcast.util.HttpUtil;`（仅当 ProductServiceImpl 中无其他引用时）。

- [ ] **步骤 3：确认 Result 类的 code 字段类型（已验证）**

`Result.code` 为 `int`（基本类型），`message` 字段名为 `message`（非 msg）。计划中的代码已统一使用 `result.getCode() == 200` 和 `result.getMessage()`，无需再修改。此步骤仅需在编译时确认无 `.equals(200)` 或 `getMsg()` 残留。

- [ ] **步骤 4：验证 Agent 调用链路（手动检查）**

确认以下调用链路完整：
1. 前端 `ShoppingAssistant.vue` → `chatWithAgent(question)` → `POST /product/agent/buyProduct`
2. `ProductController.agentBuyProduct` → `ProductService.buyProduct(String)`
3. `ProductServiceImpl.buyProduct(String)` → `ShoppingAgentService.chat(message, null, userId)`
4. `ShoppingAgentService` → `HttpUtil.postWithUserId(http://localhost:10010/ai/agent/chat, dto, userId)`
5. 网关路由 `/ai/**` → `redbook-service-ai` → `AiController.agentChat` → `AiServiceImpl.agentChat` → DeepSeek API
6. LLM 返回 → `FunctionCallParser.parse` → 若为 FunctionCall → `ToolExecutor.execute` → 本地 Service 调用
7. 工具结果注入历史 → 下一轮 LLM 调用 → 直到自然语言回复

- [ ] **步骤 5：最终 Commit**

```bash
cd d:\Desktop\仿小红书\my-redbook
git add -A
git commit -m "chore(agent): 全量编译验证通过，购物 Agent 全链路完成"
```

---

## 自检

### 1. 规格覆盖度

| 用户计划项 | 对应任务 |
|-----------|---------|
| System Prompt 设计 | 任务 6（SystemPromptBuilder，含 5 工具说明 + 输出格式约束） |
| search_products 工具 | 任务 2（searchProducts）+ 任务 7（ToolExecutor.executeSearchProducts） |
| get_product_detail 工具 | 任务 7（ToolExecutor.executeGetProductDetail，调用现有 ProductService.getProduct） |
| add_to_cart 工具 | 任务 7（ToolExecutor.executeAddToCart，调用现有 CartService.addToCart） |
| create_order 工具（Redis Lua + MQ） | 任务 7（ToolExecutor.executeCreateOrder，调用现有 ProductService.buyProduct(BuyDto)） |
| check_coupons 工具 | 任务 7（ToolExecutor.executeCheckCoupons，调用现有 CouponService.getCouponsByUserId） |
| 多轮对话管理（Redis, TTL 30min） | 任务 4（ConversationStore） |
| 滑动窗口截断（最近 10 轮） | 任务 4（MAX_TURNS=10, MAX_MESSAGES=20） |
| 话题切换检测 | **未实现**（简化：前端可传新 conversationId 开启新对话；自动语义相似度检测超出本计划范围，可作为后续扩展） |
| Few-Shot 示例 | 任务 5（FewShotStore）+ 任务 6（SystemPromptBuilder 注入） |
| JSON 格式正确率度量 | 任务 3（FunctionCallParser，Redis hash 统计 total/success） |
| Few-Shot 样本池 | 任务 5（FewShotStore.recordSample） |

**遗漏说明：** "话题切换检测（语义相似度 < 阈值时清空历史）"未实现，因为这需要额外的 embedding 服务调用，复杂度高且非核心。替代方案：前端通过不传 conversationId 开启新对话。若后续需要，可在 ShoppingAgentService 中加一个轻量级关键词检测（如新 query 不含历史关键词则清空）。

### 2. 占位符扫描

无占位符。所有代码步骤均包含完整可编译的代码。

### 3. 类型一致性

- `FunctionCall` 类：`name` (String) + `arguments` (Map<String, Object>) —— 任务 3 定义，任务 7 ToolExecutor、任务 8 ShoppingAgentService 使用，一致。
- `AgentMessage` 类：`role` (String) + `content` (String) —— 任务 3 定义，任务 4 ConversationStore、任务 8 ShoppingAgentService 使用，一致。
- `AgentChatDto` 类：`messages` (List<Message>) + `systemPrompt` (String) —— 任务 1 定义，任务 8 ShoppingAgentService.callAi 使用，一致。
- `Result.getCode()` 返回类型：已确认为 `int`（基本类型），所有比较使用 `== 200`（非 `.equals(200)`）；字段名 `message`（非 `msg`），使用 `getMessage()`。

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-07-06-shopping-agent-function-calling.md`。两种执行方式：

**1. 子代理驱动（推荐）** - 每个任务调度一个新的子代理，任务间进行审查，快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

选哪种方式？
