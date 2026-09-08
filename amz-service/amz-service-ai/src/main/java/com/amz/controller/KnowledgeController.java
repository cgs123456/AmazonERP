package com.amz.controller;

import com.amz.ai.knowledge.KnowledgeChunk;
import com.amz.ai.knowledge.KnowledgeDocumentService;
import com.amz.ai.knowledge.KnowledgeIndexService;
import com.amz.ai.knowledge.KnowledgeSearchService;
import com.amz.context.UserContext;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 店铺知识库 HTTP 接口（B-1）。
 * <p>
 * 上传解析 → 分块 → 向量化 → ES 落库；检索走混合召回 + 重排。
 * shopId 必传且强制校验归属（与 Agent 工具同一信任模型）。
 */
@Slf4j
@RestController
@RequestMapping("/ai/knowledge")
public class KnowledgeController {

    /** 与文档服务一致的单文件上限 10MB。 */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /** 允许入库的文档扩展名（Tika 内容嗅探为主，扩展名仅作第一道拦截）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "txt", "md", "markdown", "html", "htm");

    /** 检索 query 上限，避免超长文本打爆 ES multi_match。 */
    private static final int MAX_QUERY_LENGTH = 500;

    @Autowired
    private KnowledgeDocumentService documentService;

    @Autowired
    private KnowledgeIndexService indexService;

    @Autowired
    private KnowledgeSearchService searchService;

    /**
     * 上传文档并入库。POST /ai/knowledge/documents（multipart）。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("shopId") Long shopId) {
        if (file == null || file.isEmpty()) {
            return Result.failure("文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            return Result.failure("文件大小不能超过10MB");
        }
        if (!isShopAllowed(shopId)) {
            return Result.failure("无权访问该店铺数据");
        }
        String filename = file.getOriginalFilename();
        if (!isAllowedExtension(filename)) {
            return Result.failure("仅支持 PDF/Word/TXT/Markdown/HTML 文档");
        }
        try {
            return Result.success(documentService.uploadDocument(file.getBytes(), filename, shopId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("知识文档入库失败 shopId={} filename={}", shopId, filename, e);
            return Result.failure("知识文档入库失败");
        }
    }

    /**
     * 删除整篇文档（先校验归属）。DELETE /ai/knowledge/documents/{docId}?shopId=
     */
    @DeleteMapping("/documents/{docId}")
    public Result<Map<String, Object>> deleteDocument(
            @PathVariable String docId,
            @RequestParam("shopId") Long shopId) {
        if (docId == null || docId.isBlank()) {
            return Result.failure("docId 不能为空");
        }
        if (!isShopAllowed(shopId)) {
            return Result.failure("无权访问该店铺数据");
        }
        try {
            int deleted = documentService.deleteDocument(indexService.indexName(), docId.trim(), shopId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("docId", docId.trim());
            data.put("deleted", deleted);
            return Result.success(data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("知识文档删除失败 docId={} shopId={}", docId, shopId, e);
            return Result.failure("知识文档删除失败");
        }
    }

    /**
     * 检索知识库。GET /ai/knowledge/search?shopId=&query=&topN=
     */
    @GetMapping("/search")
    public Result<List<KnowledgeChunk>> search(
            @RequestParam("shopId") Long shopId,
            @RequestParam("query") String query,
            @RequestParam(value = "topN", defaultValue = "5") int topN) {
        if (!isShopAllowed(shopId)) {
            return Result.failure("无权访问该店铺数据");
        }
        if (query == null || query.isBlank()) {
            return Result.failure("query 不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            return Result.failure("query 长度不能超过500字符");
        }
        try {
            return Result.success(searchService.search(shopId, query.trim(), topN));
        } catch (Exception e) {
            log.error("知识库检索失败 shopId={}", shopId, e);
            return Result.failure("知识库检索暂时不可用");
        }
    }

    private boolean isShopAllowed(Long shopId) {
        return shopId != null && UserContext.isShopAllowed(shopId);
    }

    static boolean isAllowedExtension(String filename) {
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase());
    }
}
