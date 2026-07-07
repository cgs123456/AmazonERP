# ES 混合检索实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 笔记搜索从单路 BM25 文本检索升级为 BM25 + 向量混合检索，使用 ES 8.15 dense_vector + kNN + RRF 融合算法，提升搜索相关度。

**架构：** 新建 `EmbeddingService`（redbook-common，JDK HttpClient + Gson，OpenAI 兼容 /v1/embeddings API）；`NoteEs` 添加 dense_vector(1024) 字段 + 显式 mapping JSON；`SaveNoteToEsHandler` 保存前生成 embedding；`SearchServiceImpl` 改造为 BM25 Top50 + kNN Top50 → RRF 融合 → Top20，embedding 不可用时降级为纯 BM25。

**技术栈：** Spring Boot 3.3.5 / Spring Data Elasticsearch 5.3.x（ES Java Client 8.x）/ ES 8.15.3 / JDK HttpClient + Gson / OpenAI 兼容 Embedding API

---

## 关键设计决策

### 1. EmbeddingService 放在 redbook-common
- **原因**：note 模块（写入向量）和 search 模块（查询向量）都需要，放在公共模块避免重复
- **技术栈**：JDK HttpClient + Gson（与 redbook-common 现有 HttpUtil 风格一致，不引入 OkHttp 新依赖）
- **包名**：`com.itcast.embedding`
- **配置项**：`embedding.api-url`、`embedding.api-key`、`embedding.model`、`embedding.dims`（默认 1024）
- **降级**：API 异常返回 null，调用方降级为纯 BM25

### 2. 向量维度 1024，模型 text-embedding-v3
- 与智云星课 RAG 项目保持一致（已验证可用）
- 默认 `text-embedding-v3`（阿里云 DashScope / OpenAI 兼容）
- 维度通过 `embedding.dims` 配置，与 NoteEs @Field dims 保持同步

### 3. 显式 mapping + 启动时索引初始化
- 现有 `rb_note` 索引是动态映射，无 dense_vector 字段
- 新建 `rb_note-mapping.json` 资源文件 + `NoteEs` 添加 `@Field(type=Dense_Vector, dims=1024)` + `@Mapping` 注解
- 启动时 `IndexOperations.create()` 显式建索引（如果不存在）
- **部署注意**：如果索引已存在，需手动 `DELETE /rb_note` 后重启让应用重建

### 4. RRF 融合算法
- BM25 multi_match Top50 + kNN Top50
- RRF score = sum(1 / (k + rank_i))，k = 60（ES 默认值）
- 合并后按 RRF score 降序取 Top20
- embedding 不可用时降级为纯 BM25 Top20

### 5. SaveNoteToEsHandler 修复吞异常 bug
- 现状：`catch (Exception e) { return; }` 吞掉异常，Saga 补偿失效
- 修复：改为 `throw e`，让 ES 写入失败能触发补偿回滚

---

## 文件结构

### 新建文件
| 文件 | 职责 |
|---|---|
| `redbook-common/src/main/java/com/itcast/embedding/EmbeddingService.java` | Embedding 服务接口（embed + isAvailable） |
| `redbook-common/src/main/java/com/itcast/embedding/EmbeddingServiceImpl.java` | JDK HttpClient 实现，调用 OpenAI 兼容 /v1/embeddings |
| `redbook-service-note/src/main/resources/rb_note-mapping.json` | rb_note 索引显式 mapping（含 dense_vector） |

### 修改文件
| 文件 | 改动 |
|---|---|
| `redbook-service-note/src/main/java/com/itcast/model/pojo/NoteEs.java` | 添加 embedding 字段 + @Field(Dense_Vector, dims=1024) + @Mapping 注解 |
| `redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteToEsHandler.java` | 注入 EmbeddingService，保存前生成 embedding；修复吞异常 bug |
| `redbook-service-note/src/main/java/com/itcast/config/EsConfig.java` | 启动时检查并创建 rb_note 索引 |
| `redbook-service-search/src/main/java/com/itcast/service/impl/SearchServiceImpl.java` | BM25 + kNN + RRF 融合，降级纯 BM25 |
| `redbook-service-note/src/main/resources/application.yml` | 添加 embedding 配置块 |
| `redbook-service-search/src/main/resources/application.yml` | 添加 embedding 配置块 |

---

## 任务分解

### 任务 1：创建 EmbeddingService（redbook-common）

**文件：**
- 创建：`redbook-common/src/main/java/com/itcast/embedding/EmbeddingService.java`
- 创建：`redbook-common/src/main/java/com/itcast/embedding/EmbeddingServiceImpl.java`

- [ ] **步骤 1：创建接口**

`redbook-common/src/main/java/com/itcast/embedding/EmbeddingService.java`：
```java
package com.itcast.embedding;

/**
 * 文本向量嵌入服务（OpenAI 兼容 /v1/embeddings API）
 */
public interface EmbeddingService {

    /**
     * 生成文本的向量嵌入
     * @param text 待向量化的文本
     * @return 向量数组；服务不可用或异常返回 null
     */
    float[] embed(String text);

    /**
     * 检查嵌入服务是否可用（api-url 和 api-key 已配置）
     */
    boolean isAvailable();
}
```

- [ ] **步骤 2：创建实现**

`redbook-common/src/main/java/com/itcast/embedding/EmbeddingServiceImpl.java`：
```java
package com.itcast.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Embedding 服务实现：调用 OpenAI 兼容 /v1/embeddings API。
 *
 * 请求体：{"model":"text-embedding-v3","input":"文本","encoding_format":"float"}
 * 响应体：{"data":[{"embedding":[...],"index":0,"object":"embedding"}]}
 *
 * 异常时返回 null，调用方降级为纯 BM25 文本检索。
 */
@Service
@Slf4j
public class EmbeddingServiceImpl implements EmbeddingService {

    @Value("${embedding.api-url:}")
    private String apiUrl;

    @Value("${embedding.api-key:}")
    private String apiKey;

    @Value("${embedding.model:text-embedding-v3}")
    private String model;

    private final Gson gson = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public float[] embed(String text) {
        if (!isAvailable()) return null;
        if (text == null || text.trim().isEmpty()) return null;

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            requestBody.addProperty("input", text);
            requestBody.addProperty("encoding_format", "float");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Embedding API 调用失败: {} - {}", response.statusCode(), response.body());
                return null;
            }

            JsonObject resp = gson.fromJson(response.body(), JsonObject.class);
            JsonArray data = resp.getAsJsonArray("data");
            JsonArray embeddingArr = data.get(0).getAsJsonObject().getAsJsonArray("embedding");

            float[] result = new float[embeddingArr.size()];
            for (int i = 0; i < embeddingArr.size(); i++) {
                result[i] = embeddingArr.get(i).getAsFloat();
            }
            return result;
        } catch (Exception e) {
            log.warn("Embedding 调用异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return apiUrl != null && !apiUrl.isEmpty()
                && apiKey != null && !apiKey.isEmpty();
    }
}
```

- [ ] **步骤 3：编译验证**

运行：`mvn -pl redbook-common -am compile -q`
预期：BUILD SUCCESS

---

### 任务 2：NoteEs 添加 embedding 字段 + mapping JSON

**文件：**
- 修改：`redbook-service-note/src/main/java/com/itcast/model/pojo/NoteEs.java`
- 创建：`redbook-service-note/src/main/resources/rb_note-mapping.json`

- [ ] **步骤 1：修改 NoteEs，添加 embedding 字段和注解**

```java
package com.itcast.model.pojo;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Mapping;

@Data
@Document(indexName = "rb_note")
@Mapping(jsonPath = "rb_note-mapping.json")
public class NoteEs {
    @Id
    private Integer id;
    private String title;
    private String content;
    private String image;
    private String time;
    private String address;
    private Double longitude;
    private Double latitude;
    private Integer userId;
    /** 笔记文本向量（title + " " + content 的 embedding） */
    @Field(type = FieldType.Dense_Vector, dims = 1024)
    private float[] embedding;
}
```

- [ ] **步骤 2：创建 mapping JSON**

`redbook-service-note/src/main/resources/rb_note-mapping.json`：
```json
{
  "properties": {
    "id": { "type": "integer" },
    "title": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
    "content": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
    "image": { "type": "keyword", "index": false },
    "time": { "type": "keyword", "index": false },
    "address": { "type": "text", "analyzer": "ik_max_word" },
    "longitude": { "type": "double" },
    "latitude": { "type": "double" },
    "userId": { "type": "integer" },
    "embedding": {
      "type": "dense_vector",
      "dims": 1024,
      "index": true,
      "similarity": "cosine"
    }
  }
}
```

> **注意**：`ik_max_word` / `ik_smart` 需要安装 IK 分词器插件。如果未安装，改为标准 `standard` 分词器。

- [ ] **步骤 3：编译验证**

运行：`mvn -pl redbook-service/redbook-service-note -am compile -q`
预期：BUILD SUCCESS

---

### 任务 3：EsConfig 启动时索引初始化

**文件：** 修改 `redbook-service-note/src/main/java/com/itcast/config/EsConfig.java`

- [ ] **步骤 1：添加索引初始化逻辑**

```java
package com.itcast.config;

import com.itcast.model.pojo.NoteEs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

/**
 * Elasticsearch 配置类
 */
@Configuration
@Slf4j
public class EsConfig {

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    /**
     * 启动时检查 rb_note 索引，不存在则创建（使用 NoteEs 上的 @Mapping 注解定义的 mapping）
     */
    @PostConstruct
    public void initNoteIndex() {
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(NoteEs.class);
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
                log.info("rb_note 索引创建成功（含 dense_vector mapping）");
            } else {
                log.info("rb_note 索引已存在，跳过创建");
            }
        } catch (Exception e) {
            log.warn("rb_note 索引初始化失败（不影响启动）: {}", e.getMessage());
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn -pl redbook-service/redbook-service-note -am compile -q`
预期：BUILD SUCCESS

---

### 任务 4：修改 SaveNoteToEsHandler 生成 embedding

**文件：** 修改 `redbook-service-note/src/main/java/com/itcast/handler/impl/SaveNoteToEsHandler.java`

- [ ] **步骤 1：注入 EmbeddingService，保存前生成 embedding，修复吞异常 bug**

```java
package com.itcast.handler.impl;

import com.itcast.embedding.EmbeddingService;
import com.itcast.handler.NoteHandler;
import com.itcast.model.dto.NoteDto;
import com.itcast.model.pojo.NoteEs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(11)
@Slf4j
public class SaveNoteToEsHandler extends NoteHandler {

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private EmbeddingService embeddingService;

    @Override
    public void handle(NoteDto noteDto) throws IOException, InterruptedException {
        try {
            NoteEs noteEs = new NoteEs();
            BeanUtils.copyProperties(noteDto, noteEs);

            // 生成文本向量（title + " " + content），失败时 embedding 为 null 不影响保存
            try {
                if (embeddingService.isAvailable()) {
                    String text = buildEmbeddingText(noteDto.getTitle(), noteDto.getContent());
                    float[] embedding = embeddingService.embed(text);
                    if (embedding != null) {
                        noteEs.setEmbedding(embedding);
                    } else {
                        log.warn("笔记 embedding 生成失败，以无向量保存 noteId={}", noteDto.getId());
                    }
                }
            } catch (Exception e) {
                log.warn("笔记 embedding 异常，以无向量保存 noteId={}", noteDto.getId(), e);
            }

            elasticsearchOperations.save(noteEs);
            log.info("笔记已保存到 ES noteId={}", noteDto.getId());
        } catch (Exception e) {
            log.error("保存笔记到 ES 失败 noteId={}", noteDto.getId(), e);
            throw e;  // 修复吞异常 bug：向上抛出，触发 Saga 补偿
        }
    }

    /** 拼接 embedding 文本：title + " " + content */
    private String buildEmbeddingText(String title, String content) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) {
            sb.append(title);
        }
        if (content != null && !content.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(content);
        }
        return sb.toString();
    }

    @Override
    public void compensate(NoteDto noteDto) {
        if (noteDto.getId() != null) {
            try {
                NoteEs noteEs = new NoteEs();
                noteEs.setId(Math.toIntExact(noteDto.getId()));
                elasticsearchOperations.delete(noteEs);
                log.info("补偿操作：已从 ES 删除笔记，noteId: {}", noteDto.getId());
            } catch (Exception e) {
                log.error("补偿操作失败：从 ES 删除笔记 noteId: {} 时出错", noteDto.getId(), e);
            }
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn -pl redbook-service/redbook-service-note -am compile -q`
预期：BUILD SUCCESS

---

### 任务 5：修改 SearchServiceImpl 实现 BM25 + kNN + RRF 融合

**文件：** 修改 `redbook-service-search/src/main/java/com/itcast/service/impl/SearchServiceImpl.java`

- [ ] **步骤 1：实现混合检索 + RRF 融合**

```java
package com.itcast.service.impl;

import com.itcast.client.UserClient;
import com.itcast.constant.RedisConstant;
import com.itcast.context.UserContext;
import com.itcast.embedding.EmbeddingService;
import com.itcast.mapper.HistoryMapper;
import com.itcast.model.pojo.History;
import com.itcast.model.pojo.Note;
import com.itcast.model.vo.NoteVo;
import com.itcast.result.Result;
import com.itcast.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SearchServiceImpl implements SearchService {

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private UserClient userClient;

    @Autowired
    private HistoryMapper historyMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private EmbeddingService embeddingService;

    /** BM25 检索 Top N */
    private static final int BM25_TOP = 50;
    /** kNN 检索 Top N */
    private static final int KNN_TOP = 50;
    /** RRF 融合后返回 Top N */
    private static final int FINAL_TOP = 20;
    /** RRF 常数 k（默认 60） */
    private static final int RRF_K = 60;

    @Override
    public Result<List<NoteVo>> search(String key) {
        List<Note> notes = hybridSearch(key);

        // 设置 vo
        List<NoteVo> noteVos = new ArrayList<>();
        for (Note note : notes) {
            NoteVo noteVo = new NoteVo();
            BeanUtils.copyProperties(note, noteVo);
            try {
                noteVo.setUser(userClient.getUserById(note.getUserId()).getData());
            } catch (Exception e) {
                log.warn("获取用户信息失败 userId={}", note.getUserId());
            }
            noteVos.add(noteVo);
        }

        // 保存搜索记录（去重）
        saveSearchHistory(key);

        // 保存热度
        redisTemplate.opsForZSet().incrementScore(RedisConstant.NOTE_SCORE, key, 1);
        return Result.success(noteVos);
    }

    /**
     * 混合检索：BM25 Top50 + kNN Top50 → RRF 融合 → Top20
     * embedding 不可用时降级为纯 BM25 Top20
     */
    private List<Note> hybridSearch(String key) {
        // 1. BM25 文本检索 Top50
        List<SearchHit<Note>> bm25Hits = bm25Search(key);
        log.info("BM25 检索结果数: {}", bm25Hits.size());

        // 2. 尝试 kNN 向量检索 Top50
        List<SearchHit<Note>> knnHits = new ArrayList<>();
        float[] queryVector = null;
        try {
            if (embeddingService.isAvailable()) {
                queryVector = embeddingService.embed(key);
                if (queryVector != null) {
                    knnHits = knnSearch(queryVector);
                    log.info("kNN 检索结果数: {}", knnHits.size());
                }
            }
        } catch (Exception e) {
            log.warn("kNN 向量检索异常，降级纯 BM25: {}", e.getMessage());
        }

        // 3. 融合
        if (queryVector == null || knnHits.isEmpty()) {
            // 降级：纯 BM25 Top20
            return takeTop(bm25Hits, FINAL_TOP);
        }

        // RRF 融合 → Top20
        return rrfFusion(bm25Hits, knnHits, FINAL_TOP);
    }

    /** BM25 multi_match 检索 */
    private List<SearchHit<Note>> bm25Search(String key) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(key)
                        .fields("title", "content")
                ))
                .withPageable(PageRequest.of(0, BM25_TOP))
                .build();

        SearchHits<Note> searchHits = elasticsearchOperations.search(
                query, Note.class, IndexCoordinates.of("rb_note"));
        return searchHits.getSearchHits();
    }

    /** kNN 向量检索 */
    private List<SearchHit<Note>> knnSearch(float[] queryVector) {
        // 转换 float[] → List<Float>
        List<Float> vectorList = new ArrayList<>(queryVector.length);
        for (float f : queryVector) vectorList.add(f);

        Query query = NativeQuery.builder()
                .withQuery(q -> q.knn(k -> k
                        .field("embedding")
                        .queryVector(vectorList)
                        .numCandidates((long) KNN_TOP * 2)
                ))
                .withPageable(PageRequest.of(0, KNN_TOP))
                .build();

        SearchHits<Note> searchHits = elasticsearchOperations.search(
                query, Note.class, IndexCoordinates.of("rb_note"));
        return searchHits.getSearchHits();
    }

    /**
     * RRF 融合算法
     * score = sum(1 / (k + rank_i))，k=60
     * rank 从 1 开始（第 1 名 rank=1）
     */
    private List<Note> rrfFusion(List<SearchHit<Note>> bm25Hits,
                                  List<SearchHit<Note>> knnHits,
                                  int topN) {
        // 用 LinkedHashMap 保持插入顺序，最终按 score 排序
        Map<Integer, Double> noteScoreMap = new HashMap<>();
        Map<Integer, Note> noteMap = new HashMap<>();

        // BM25 排名累加
        for (int i = 0; i < bm25Hits.size(); i++) {
            Note note = bm25Hits.get(i).getContent();
            Integer id = note.getId();
            double score = 1.0 / (RRF_K + i + 1);
            noteScoreMap.merge(id, score, Double::sum);
            noteMap.putIfAbsent(id, note);
        }

        // kNN 排名累加
        for (int i = 0; i < knnHits.size(); i++) {
            Note note = knnHits.get(i).getContent();
            Integer id = note.getId();
            double score = 1.0 / (RRF_K + i + 1);
            noteScoreMap.merge(id, score, Double::sum);
            noteMap.putIfAbsent(id, note);
        }

        // 按 RRF score 降序取 TopN
        return noteScoreMap.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topN)
                .map(e -> noteMap.get(e.getKey()))
                .toList();
    }

    /** 从 SearchHit 列表取 Top N */
    private List<Note> takeTop(List<SearchHit<Note>> hits, int topN) {
        List<Note> result = new ArrayList<>();
        for (int i = 0; i < Math.min(hits.size(), topN); i++) {
            result.add(hits.get(i).getContent());
        }
        return result;
    }

    /** 保存搜索记录（去重） */
    private void saveSearchHistory(String key) {
        try {
            Integer currentUserId = UserContext.getUserId();
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<History> queryWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            queryWrapper.eq(History::getUserId, currentUserId)
                       .eq(History::getHistory, key);
            History existingHistory = historyMapper.selectOne(queryWrapper);

            if (existingHistory != null) {
                log.info("搜索记录已存在，更新: userId={}, key={}", currentUserId, key);
            } else {
                History history = new History();
                history.setHistory(key);
                history.setUserId(currentUserId);
                historyMapper.insert(history);
            }
        } catch (Exception e) {
            log.info("用户搜索记录处理异常: {}", e.getMessage());
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn -pl redbook-service/redbook-service-search -am compile -q`
预期：BUILD SUCCESS

---

### 任务 6：配置 application.yml

**文件：**
- 修改：`redbook-service-note/src/main/resources/application.yml`
- 修改：`redbook-service-search/src/main/resources/application.yml`

- [ ] **步骤 1：note 模块添加 embedding 配置**

在 `redbook-service-note/src/main/resources/application.yml` 末尾追加：
```yaml

# Embedding 服务配置（用于笔记向量检索）
embedding:
  api-url: ${EMBEDDING_API_URL:}
  api-key: ${EMBEDDING_API_KEY:}
  model: ${EMBEDDING_MODEL:text-embedding-v3}
  dims: ${EMBEDDING_DIMS:1024}
```

- [ ] **步骤 2：search 模块添加 embedding 配置**

在 `redbook-service-search/src/main/resources/application.yml` 末尾追加：
```yaml

# Embedding 服务配置（用于笔记向量检索）
embedding:
  api-url: ${EMBEDDING_API_URL:}
  api-key: ${EMBEDDING_API_KEY:}
  model: ${EMBEDDING_MODEL:text-embedding-v3}
  dims: ${EMBEDDING_DIMS:1024}
```

- [ ] **步骤 3：编译验证**

运行：`mvn compile -q`
预期：BUILD SUCCESS（exit code 0）

---

### 任务 7：全量编译与链路验证

- [ ] **步骤 1：全项目编译**

运行：`mvn compile -q`
预期：BUILD SUCCESS（exit code 0）

- [ ] **步骤 2：验证写入链路**

确认写入链路：
```
笔记发布 → SaveNoteToEsHandler(@Order(11))
  → EmbeddingService.embed(title + " " + content)
  → NoteEs.embedding 写入 ES rb_note 索引（dense_vector, 1024 维）
```

- [ ] **步骤 3：验证查询链路**

确认查询链路：
```
GET /search/search/{key}
  → SearchServiceImpl.search(key)
  → hybridSearch(key)
    → BM25 multi_match Top50
    → EmbeddingService.embed(key) → kNN Top50
    → RRF 融合 → Top20
    → embedding 不可用 → 纯 BM25 Top20
```

- [ ] **步骤 4：部署注意事项**

> **重要**：如果 `rb_note` 索引已存在（动态映射创建的旧索引），需要先删除再重启应用：
> ```bash
> curl -X DELETE "http://121.37.250.15:9200/rb_note"
> ```
> 重启后 EsConfig.initNoteIndex() 会用新 mapping（含 dense_vector）重建索引。
> 然后需要重新发布笔记或手动同步现有笔记到 ES（生成 embedding）。

---

## 自检

### 1. 规格覆盖度
- ✅ BM25 + 向量混合检索 → 任务 5 hybridSearch
- ✅ ES 8.15 dense_vector + kNN → 任务 2 mapping + 任务 5 knnSearch
- ✅ RRF 融合算法 → 任务 5 rrfFusion
- ✅ 笔记发布时生成 embedding → 任务 4 SaveNoteToEsHandler
- ✅ embedding 降级为 BM25 → 任务 5 降级分支

### 2. 占位符扫描
- 无 TODO/待定/占位符
- 所有代码块完整

### 3. 类型一致性
- EmbeddingService.embed() 返回 float[]：任务 1、任务 4、任务 5 一致
- NoteEs.embedding 类型 float[]：任务 2、任务 4 一致
- @Order(11) SaveNoteToEsHandler：与改进2计划一致（改进2把 @Order(10) → @Order(11)）
- RRF_K = 60：任务 5 一致

### 4. 依赖冲突检查
- 改进2和改进3都修改 SaveNoteToEsHandler 的 @Order：
  - 改进2：@Order(10) → @Order(11)
  - 改进3：保持 @Order(11)（基于改进2的结果）
- **执行顺序**：先执行改进2，再执行改进3（避免 @Order 冲突）
