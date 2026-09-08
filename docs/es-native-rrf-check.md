# ES 原生 RRF 可用性验证（B-2）

> 背景：商品搜索当前用**应用层 RRF**（`SearchServiceImpl` 内对 BM25 TopN + kNN TopN
> 按 `1/(rrfK + rank)` 融合）。ES 8.15 自带原生 `rrf` 查询，理论上可把融合下推，
> 但其可用性与 license 相关，不能默认可用——先用本指南验证，再决定是否迁移。

## 前置条件

- Elasticsearch 8.15.3 运行中（`docker-compose up elasticsearch`）
- 索引 `amz_product` 存在且有 `embedding` dense_vector 字段数据

## 步骤 1：确认版本与 license

```bash
curl -s localhost:9200/ | grep -o '"number"[^,]*'
curl -s localhost:9200/_license | grep -o '"type"[^,]*'
```

## 步骤 2：原生 rrf 探针（rank_window_size 50）

```bash
curl -s -X POST localhost:9200/amz_product/_search \
  -H 'Content-Type: application/json' -d '{
    "query": {
      "rrf": {
        "retrievers": [
          { "standard": { "query": { "multi_match": {
            "query": "无线耳机",
            "fields": ["title", "content", "summary"]
          } } } },
          { "knn": {
            "field": "embedding",
            "query_vector": [0.01, 0.02],
            "k": 50,
            "num_candidates": 100
          } }
        ],
        "rank_window_size": 50,
        "rank_constant": 60
      }
    },
    "size": 20
  }' | head -c 600
```

> 注意：`query_vector` 维度必须与 mapping 的 `dims` 一致（示例用 2 维占位，
> 实测时替换为真实查询向量；维度不对会直接 400，可顺带确认映射）。

## 步骤 3：判定与后续

| 结果 | 动作 |
|---|---|
| 200 且正常返回融合结果 | 可迁移：将 `hybridSearch` 改为单次原生 `rrf` 查询，`rank_window_size` 50→100 做 A/B（`search.retrieval.*` 已全部外置为配置项，无需改代码）；应用层融合保留为降级分支 |
| 4xx `unknown query [rrf]` / license 相关拒绝 | 不可迁移：维持现状，仅调 `search.retrieval.rrf-k` 与 `search.retrieval.bm25-fields` 做 A/B |

## 可调参数（无需改代码，环境变量/配置覆盖）

| 配置项 | 缺省 | 说明 |
|---|---|---|
| `search.retrieval.bm25-top` | 50 | BM25 路召回数 |
| `search.retrieval.knn-top` | 50 | kNN 路召回数 |
| `search.retrieval.knn-candidates` | 100 | kNN 候选集 |
| `search.retrieval.final-top` | 20 | 融合后截断 |
| `search.retrieval.rrf-k` | 60.0 | RRF 平滑常数 |
| `search.retrieval.bm25-fields` | title,content,summary | BM25 字段（逗号分隔，支持 `title^3` 权重语法） |
