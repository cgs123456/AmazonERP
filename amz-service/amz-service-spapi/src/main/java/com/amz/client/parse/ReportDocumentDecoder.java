package com.amz.client.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * SP-API 报表文档解码器：按文档元数据解压（GZIP / 无压缩）。
 * <p>
 * 结算原表（GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE）以 TSV + GZIP 下发，
 * 文档元数据中的 compressionAlgorithm 取值 GZIP 或缺省（无压缩）。
 * <p>
 * 独立成静态工具类而非埋在客户端里 —— 便于对「GZIP 解压 / 明文透传 /
 * 不支持的压缩格式」三种路径直接做单测（真实联调无凭证时，这是唯一能验证的层）。
 */
public final class ReportDocumentDecoder {

    private ReportDocumentDecoder() {
    }

    /**
     * 解码报表文档字节为文本。
     *
     * @param raw                   文档原始字节
     * @param compressionAlgorithm  文档元数据中的压缩格式（GZIP / null / 空串 = 无压缩）
     * @return 解码后的文本（UTF-8）
     * @throws IOException              GZIP 解压失败（数据损坏或非 GZIP 字节流）
     * @throws IllegalArgumentException 压缩格式不支持（既非 GZIP 也非无压缩）
     */
    public static String decode(byte[] raw, String compressionAlgorithm) throws IOException {
        if (raw == null) {
            return "";
        }
        if (compressionAlgorithm == null || compressionAlgorithm.isBlank()
                || "NONE".equalsIgnoreCase(compressionAlgorithm)) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        if ("GZIP".equalsIgnoreCase(compressionAlgorithm)) {
            return gunzip(raw);
        }
        throw new IllegalArgumentException(
                "Unsupported report document compression: " + compressionAlgorithm);
    }

    private static String gunzip(byte[] raw) throws IOException {
        try (Reader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(raw)), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }
}
