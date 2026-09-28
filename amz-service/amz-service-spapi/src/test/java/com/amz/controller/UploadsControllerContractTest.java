package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.AmazonUploadsRealClient;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Uploads 边界：店铺/角色守卫、原始字节透传、失败不伪装成功")
class UploadsControllerContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String RESOURCE = "/messaging/v1/orders/123-1234567-1234567/messages/legalDisclosure";

    @Test
    @DisplayName("multipart 文件原样交给 Uploads 客户端并返回 uploadDestinationId")
    void uploadForwardsFileBytesAndReturnsDestinationId() throws Exception {
        AmazonUploadsRealClient client = mock(AmazonUploadsRealClient.class);
        byte[] content = "synthetic invoice".getBytes(StandardCharsets.UTF_8);
        when(client.createUploadDestinationAndUpload(eq(SHOP_ID), eq(MARKETPLACE_ID),
                eq(RESOURCE), eq("application/pdf"), any(byte[].class)))
                .thenReturn("dest-1");

        Result<String> result = controller(client).upload(SHOP_ID, MARKETPLACE_ID, RESOURCE,
                "application/pdf", file("invoice.pdf", "application/pdf", content));

        assertEquals(200, result.getCode());
        assertEquals("dest-1", result.getData());
        verify(client).createUploadDestinationAndUpload(eq(SHOP_ID), eq(MARKETPLACE_ID),
                eq(RESOURCE), eq("application/pdf"), any(byte[].class));
    }

    @Test
    @DisplayName("未显式传 contentType 时使用 multipart 声明值")
    void uploadUsesMultipartContentTypeWhenNotOverridden() throws Exception {
        AmazonUploadsRealClient client = mock(AmazonUploadsRealClient.class);
        when(client.createUploadDestinationAndUpload(any(), anyString(), anyString(),
                anyString(), any(byte[].class))).thenReturn("dest-2");

        Result<String> result = controller(client).upload(SHOP_ID, MARKETPLACE_ID, RESOURCE,
                null, file("invoice.pdf", "application/pdf", new byte[] {1, 2, 3}));

        assertEquals(200, result.getCode());
        verify(client).createUploadDestinationAndUpload(SHOP_ID, MARKETPLACE_ID, RESOURCE,
                "application/pdf", new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("空文件在调用上游前失败")
    void emptyFileFailsBeforeClientCall() {
        AmazonUploadsRealClient client = mock(AmazonUploadsRealClient.class);

        Result<String> result = controller(client).upload(SHOP_ID, MARKETPLACE_ID, RESOURCE,
                "application/pdf", file("empty.pdf", "application/pdf", new byte[0]));

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("empty"), result.getMessage());
        verify(client, never()).createUploadDestinationAndUpload(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺 resource 或 marketplaceId 时在调用上游前失败")
    void missingRequiredFieldsFailBeforeClientCall() {
        AmazonUploadsRealClient client = mock(AmazonUploadsRealClient.class);
        MockMultipartFile file = file("invoice.pdf", "application/pdf", new byte[] {1});

        Result<String> missingResource = controller(client).upload(
                SHOP_ID, MARKETPLACE_ID, "  ", "application/pdf", file);
        Result<String> missingMarketplace = controller(client).upload(
                SHOP_ID, "  ", RESOURCE, "application/pdf", file);

        assertEquals(400, missingResource.getCode());
        assertEquals(400, missingMarketplace.getCode());
        verify(client, never()).createUploadDestinationAndUpload(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("上游失败保留状态码与平台错误，不返回成功")
    void upstreamFailureIsExplicit() {
        AmazonUploadsRealClient client = mock(AmazonUploadsRealClient.class);
        when(client.createUploadDestinationAndUpload(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("upload failed status=403"
                        + " path=/upload/invoice.pdf"));

        Result<String> result = controller(client).upload(SHOP_ID, MARKETPLACE_ID, RESOURCE,
                "application/pdf", file("invoice.pdf", "application/pdf", new byte[] {1}));

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("status=403"), result.getMessage());
        assertFalse(result.getMessage().contains("success"));
    }

    @Test
    @DisplayName("上传端点必须同时具备店铺隔离与写角色守卫")
    void uploadEndpointIsGuarded() throws Exception {
        Method method = UploadsController.class.getMethod("upload", Long.class, String.class,
                String.class, String.class, MultipartFile.class);

        assertTrue(method.isAnnotationPresent(ShopScoped.class));
        RequireRole role = method.getAnnotation(RequireRole.class);
        assertTrue(role != null, "上传是写操作，必须声明角色");
        List<String> roles = List.of(role.value());
        assertTrue(roles.contains("OPERATOR") && roles.contains("ADMIN"), roles.toString());
        assertFalse(roles.contains("VIEWER"), roles.toString());
    }

    @Test
    @DisplayName("超过 multipart 上限的文件在读取字节和调用上游前失败")
    void oversizedFileFailsBeforeReadingBytesOrClientCall() throws Exception {
        AmazonUploadsRealClient client = mock(AmazonUploadsRealClient.class);
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(DataSize.ofMegabytes(1).toBytes() + 1);
        doThrow(new AssertionError("不得读取超限文件")).when(oversized).getBytes();

        UploadsController controller = controller(client);
        ReflectionTestUtils.setField(controller, "maxFileSize", DataSize.ofMegabytes(1));

        Result<String> result = controller.upload(SHOP_ID, MARKETPLACE_ID, RESOURCE,
                "application/pdf", oversized);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("max size"), result.getMessage());
        verify(oversized, never()).getBytes();
        verify(client, never()).createUploadDestinationAndUpload(any(), any(), any(), any(), any());
    }

    private static UploadsController controller(AmazonUploadsRealClient client) {
        UploadsController controller = new UploadsController();
        ReflectionTestUtils.setField(controller, "client", client);
        return controller;
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }
}
