package com.amz.service;

import com.amz.model.vo.ProductVo;
import com.amz.result.Result;

import java.util.List;

/**
 * 商品搜索服务接口。
 * <p>
 * 支持商品 ES 混合检索（BM25 + kNN 向量 → RRF 融合）。
 */
public interface SearchService {
    Result<List<ProductVo>> search(String key);
}
