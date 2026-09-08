package com.amz.controller;

import com.amz.ai.knowledge.KnowledgeChunk;
import com.amz.ai.knowledge.KnowledgeDocumentService;
import com.amz.ai.knowledge.KnowledgeIndexService;
import com.amz.ai.knowledge.KnowledgeSearchService;
import com.amz.context.UserContext;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KnowledgeController 参数与归属校验测试")
class KnowledgeControllerTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private static KnowledgeController controller(Object document, Object index, Object search) {
        KnowledgeController controller = new KnowledgeController();
        ReflectionTestUtils.setField(controller, "documentService", document);
        ReflectionTestUtils.setField(controller, "indexService", index);
        ReflectionTestUtils.setField(controller, "searchService", search);
        return controller;
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空文件直接失败")
    void testUploadEmptyFails() {
        KnowledgeController controller = controller(
                mock(KnowledgeDocumentService.class),
                mock(KnowledgeIndexService.class),
                mock(KnowledgeSearchService.class));
        MockMultipartFile empty = new MockMultipartFile(
                "file", "a.pdf", "application/pdf", new byte[0]);
        Result<Map<String, Object>> result = controller.uploadDocument(empty, 1L);
        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("文件不能为空"));
    }

    @Test
    @DisplayName("非授权店铺上传被拒绝")
    void testUploadShopDenied() {
        UserContext.setShops(new ArrayList<>(List.of(2L)));
        KnowledgeController controller = controller(
                mock(KnowledgeDocumentService.class),
                mock(KnowledgeIndexService.class),
                mock(KnowledgeSearchService.class));
        Result<Map<String, Object>> result = controller.uploadDocument(file("a.pdf", "hello"), 1L);
        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("无权访问该店铺数据"));
    }

    @Test
    @DisplayName("非法扩展名被拒绝")
    void testUploadExtensionDenied() {
        KnowledgeController controller = controller(
                mock(KnowledgeDocumentService.class),
                mock(KnowledgeIndexService.class),
                mock(KnowledgeSearchService.class));
        Result<Map<String, Object>> result = controller.uploadDocument(file("a.exe", "hello"), 1L);
        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("仅支持"));
    }

    @Test
    @DisplayName("合法上传透传服务结果")
    void testUploadSuccess() throws Exception {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        when(documentService.uploadDocument(any(), anyString(), anyLong()))
                .thenReturn(Map.of("docId", "d", "indexed", 1));
        KnowledgeController controller = controller(
                documentService, mock(KnowledgeIndexService.class), mock(KnowledgeSearchService.class));
        Result<Map<String, Object>> result = controller.uploadDocument(file("a.pdf", "hello"), 1L);
        assertEquals(200, result.getCode());
        assertEquals("d", result.getData().get("docId"));
    }

    @Test
    @DisplayName("删除成功返回删除块数")
    void testDeleteSuccess() throws Exception {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        when(indexService.indexName()).thenReturn("idx");
        when(documentService.deleteDocument("idx", "d", 1L)).thenReturn(3);
        KnowledgeController controller = controller(
                documentService, indexService, mock(KnowledgeSearchService.class));
        Result<Map<String, Object>> result = controller.deleteDocument("d", 1L);
        assertEquals(200, result.getCode());
        assertEquals(3, result.getData().get("deleted"));
    }

    @Test
    @DisplayName("检索成功返回重排结果")
    void testSearchSuccess() throws Exception {
        KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
        KnowledgeChunk chunk = KnowledgeChunk.builder().content("c").build();
        when(searchService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(chunk));
        KnowledgeController controller = controller(
                mock(KnowledgeDocumentService.class),
                mock(KnowledgeIndexService.class),
                searchService);
        Result<List<KnowledgeChunk>> result = controller.search(1L, "退货", 5);
        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
    }
}
