package com.amz.client.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表文档解码器单元测试。
 * <p>
 * 覆盖：GZIP 解压、明文透传、null / NONE 压缩、损坏 GZIP 流、不支持的压缩格式。
 * 无凭证环境下，这是下载链路唯一能被完整验证的层。
 */
@DisplayName("ReportDocumentDecoder 报表文档解码测试")
class ReportDocumentDecoderTest {

    @Test
    @DisplayName("GZIP 压缩文档应解压为原文")
    void decodeGzip() throws IOException {
        String original = "settlement-id\torder-id\tamount\n900001\t111-0000001-0000001\t29.99\n";
        assertEquals(original, ReportDocumentDecoder.decode(gzip(original), "GZIP"));
        assertEquals(original, ReportDocumentDecoder.decode(gzip(original), "gzip"));
    }

    @Test
    @DisplayName("无压缩文档应按 UTF-8 透传")
    void decodePlain() throws IOException {
        String original = "a,b,c\n1,2,3\n";
        assertEquals(original, ReportDocumentDecoder.decode(original.getBytes(StandardCharsets.UTF_8), null));
        assertEquals(original, ReportDocumentDecoder.decode(original.getBytes(StandardCharsets.UTF_8), ""));
        assertEquals(original, ReportDocumentDecoder.decode(original.getBytes(StandardCharsets.UTF_8), "NONE"));
    }

    @Test
    @DisplayName("null 字节流应返回空串而非 NPE")
    void decodeNull() throws IOException {
        assertEquals("", ReportDocumentDecoder.decode(null, null));
    }

    @Test
    @DisplayName("损坏的 GZIP 字节流应抛 IOException（而非静默返回乱码）")
    void decodeCorruptGzip() {
        byte[] notGzip = "plain text pretending to be gzip".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ReportDocumentDecoder.decode(notGzip, "GZIP"));
    }

    @Test
    @DisplayName("不支持的压缩格式应抛 IllegalArgumentException")
    void decodeUnsupportedCompression() {
        byte[] raw = "x".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ReportDocumentDecoder.decode(raw, "ZIP"));
        assertTrue(ex.getMessage().contains("ZIP"));
    }

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }
}
