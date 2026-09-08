package com.amz.ai.knowledge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库 ES 访问层（JDK HttpClient 直调 REST，与 amz-common 现有风格一致）。
 * <p>
 * 不引入 Spring Data Elasticsearch 的原因：
 * <ol>
 *   <li>知识索引与商品索引分属不同服务，避免版本/配置耦合；</li>
 *   <li>DSL（含未来原生 rrf）用字符串模板最直接，升级 ES 无需等客户端版本；</li>
 *   <li>DSL 构造抽成纯静态方法，可单测断言查询形状，无需 live ES。</li>
 * </ol>
 */
@Slf4j
@Component
public class KnowledgeEsClient {

    private static final Gson GSON = new Gson();

    @Value("${knowledge.es.base-url:http://localhost:9200}")
    private String baseUrl;

    @Value("${knowledge.es.index:amz_knowledge_base}")
    private String defaultIndex;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String defaultIndex() {
        return defaultIndex;
    }

    /**
     * 索引不存在则创建（含 mapping）。已存在直接返回 true。
     */
    public boolean ensureIndex(String index, int vectorDims) throws Exception {
        if (indexExists(index)) {
            return true;
        }
        String mapping = buildMapping(vectorDims);
        HttpResponse<String> resp = request("PUT", "/" + index, mapping);
        if (resp.statusCode() != 200) {
            log.warn("知识库索引创建失败 index={} status={} body={}", index, resp.statusCode(), truncate(resp.body()));
            return false;
        }
        log.info("知识库索引已创建 index={} dims={}", index, vectorDims);
        return true;
    }

    /**
     * 建库 mapping：content 走 standard 分词（中文效果弱于 IK，先跑通再演进，见 plan B-1），
     * embedding 为 cosine dense_vector（dims 可配，默认 1536 对齐 OpenAI 系）。
     */
    static String buildMapping(int vectorDims) {
        return "{\"mappings\":{\"properties\":{"
                + "\"doc_id\":{\"type\":\"keyword\"},"
                + "\"shop_id\":{\"type\":\"long\"},"
                + "\"filename\":{\"type\":\"keyword\"},"
                + "\"chunk_index\":{\"type\":\"integer\"},"
                + "\"content\":{\"type\":\"text\",\"analyzer\":\"standard\","
                + "\"fields\":{\"suggest\":{\"type\":\"search_as_you_type\"}}},"
                + "\"embedding\":{\"type\":\"dense_vector\",\"dims\":" + vectorDims
                + ",\"index\":true,\"similarity\":\"cosine\"},"
                + "\"create_time\":{\"type\":\"date\",\"format\":\"strict_date_optional_time||epoch_millis\"}"
                + "}}}";
    }

    public boolean indexExists(String index) throws Exception {
        HttpResponse<String> resp = request("HEAD", "/" + index, null);
        return resp.statusCode() == 200;
    }

    /**
     * 索引单个文本块（幂等：docId 由调用方按 {uuid}#{序号} 生成）。
     */
    public void indexChunk(String index, String id, Long shopId, String docId,
                           String filename, int chunkIndex, String content,
                           float[] embedding) throws Exception {
        JsonObject doc = new JsonObject();
        doc.addProperty("doc_id", docId);
        doc.addProperty("shop_id", shopId);
        doc.addProperty("filename", filename);
        doc.addProperty("chunk_index", chunkIndex);
        doc.addProperty("content", content);
        doc.addProperty("create_time", System.currentTimeMillis());
        if (embedding != null && embedding.length > 0) {
            JsonArray vec = new JsonArray();
            for (float v : embedding) {
                vec.add(v);
            }
            doc.add("embedding", vec);
        }
        HttpResponse<String> resp = request("PUT", "/" + index + "/_doc/" + id, GSON.toJson(doc));
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new IllegalStateException("ES 索引写入失败 status=" + resp.statusCode()
                    + " body=" + truncate(resp.body()));
        }
    }

    /**
     * BM25 路检索，返回按排名的 {id, chunk}。
     */
    public List<ScoredChunk> searchBm25(String index, List<Long> shopIds, String query, int size)
            throws Exception {
        StringBuilder filter = new StringBuilder();
        if (shopIds != null && !shopIds.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Long id : shopIds) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(id);
            }
            filter.append(",\"filter\":[{\"terms\":{\"shop_id\":[").append(ids).append("]}}]");
        }
        String dsl = "{\"size\":" + size
                + ",\"_source\":[\"doc_id\",\"filename\",\"chunk_index\",\"content\",\"shop_id\"]"
                + ",\"query\":{\"bool\":{\"must\":[{\"multi_match\":{\"query\":" + GSON.toJson(query)
                + ",\"fields\":[\"content\"]}}]" + filter + "}}}";
        return parseHits(postSearch(index, dsl));
    }

    /**
     * kNN 路检索，返回按排名的 {id, chunk}。
     */
    public List<ScoredChunk> searchKnn(String index, List<Long> shopIds, float[] vector, int size)
            throws Exception {
        StringBuilder dsl = new StringBuilder(256);
        dsl.append("{\"size\":").append(size);
        dsl.append(",\"_source\":[\"doc_id\",\"filename\",\"chunk_index\",\"content\",\"shop_id\"]");
        dsl.append(",\"knn\":{\"field\":\"embedding\",\"query_vector\":[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                dsl.append(',');
            }
            dsl.append(vector[i]);
        }
        dsl.append("],\"k\":").append(size).append(",\"num_candidates\":100");
        if (shopIds != null && !shopIds.isEmpty()) {
            dsl.append(",\"filter\":{\"terms\":{\"shop_id\":[");
            StringBuilder ids = new StringBuilder();
            for (Long id : shopIds) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(id);
            }
            dsl.append(ids).append("]}}");
        }
        dsl.append("}}");
        return parseHits(postSearch(index, dsl.toString()));
    }

    private String postSearch(String index, String dsl) throws Exception {
        HttpResponse<String> resp = request("POST", "/" + index + "/_search", dsl);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ES 检索失败 status=" + resp.statusCode()
                    + " body=" + truncate(resp.body()));
        }
        return resp.body();
    }

    /**
     * 按文档 ID 删除其全部块（delete_by_query）。
     *
     * @return 删除条数（-1 表示请求失败，调用方自行降级）
     */
    public int deleteByDocId(String index, String docId) throws Exception {
        String dsl = "{\"query\":{\"term\":{\"doc_id\":" + GSON.toJson(docId) + "}}}";
        HttpResponse<String> resp = request("POST", "/" + index + "/_delete_by_query", dsl);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ES 删除失败 status=" + resp.statusCode());
        }
        try {
            return JsonParser.parseString(resp.body()).getAsJsonObject().get("deleted").getAsInt();
        } catch (Exception e) {
            log.warn("删除响应解析失败 index={} docId={}", index, docId, e);
            return -1;
        }
    }

    /**
     * 按文档 ID 取一条块（用于删除前的归属校验）。
     */
    public JsonObject findOneByDocId(String index, String docId) throws Exception {
        String dsl = "{\"size\":1,\"_source\":[\"doc_id\",\"shop_id\"],"
                + "\"query\":{\"term\":{\"doc_id\":" + GSON.toJson(docId) + "}}}";
        HttpResponse<String> resp = request("POST", "/" + index + "/_search", dsl);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ES 查询失败 status=" + resp.statusCode());
        }
        JsonArray hits = JsonParser.parseString(resp.body())
                .getAsJsonObject().getAsJsonObject("hits").getAsJsonArray("hits");
        if (hits == null || hits.size() == 0) {
            return null;
        }
        return hits.get(0).getAsJsonObject().getAsJsonObject("_source");
    }

    /** 解析 hits 为有序块列表（id + 内容）。 */
    static List<ScoredChunk> parseHits(String responseBody) {
        List<ScoredChunk> out = new ArrayList<>();
        JsonArray hits = JsonParser.parseString(responseBody)
                .getAsJsonObject().getAsJsonObject("hits").getAsJsonArray("hits");
        if (hits == null) {
            return out;
        }
        for (int i = 0; i < hits.size(); i++) {
            JsonObject hit = hits.get(i).getAsJsonObject();
            JsonObject src = hit.has("_source") ? hit.getAsJsonObject("_source") : new JsonObject();
            ScoredChunk sc = new ScoredChunk();
            sc.setId(hit.has("_id") ? hit.get("_id").getAsString() : ("hit-" + i));
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocId(getString(src, "doc_id"));
            chunk.setFilename(getString(src, "filename"));
            chunk.setChunkIndex(getInt(src, "chunk_index"));
            chunk.setContent(getString(src, "content"));
            sc.setChunk(chunk);
            out.add(sc);
        }
        return out;
    }

    private static String getString(JsonObject src, String key) {
        try {
            return (src.has(key) && !src.get(key).isJsonNull()) ? src.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int getInt(JsonObject src, String key) {
        try {
            return (src.has(key) && !src.get(key).isJsonNull()) ? src.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        switch (method) {
            case "PUT":
                builder.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                break;
            case "POST":
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                break;
            case "HEAD":
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                break;
            default:
                builder.GET();
                break;
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 带排名的块（RRF 融合输入）。
     */
    public static class ScoredChunk {
        private String id;
        private KnowledgeChunk chunk;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public KnowledgeChunk getChunk() {
            return chunk;
        }

        public void setChunk(KnowledgeChunk chunk) {
            this.chunk = chunk;
        }
    }
}
