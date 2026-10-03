package com.amz.util;

import com.amz.exception.CodeErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对象存储未配置时必须拒绝，而不是拿占位符去撞 SDK。
 * <p>
 * 动因：{@code oss.accessKeyId} 的默认值是 {@code your-access-key-id}、
 * {@code accessKeySecret} 默认是空串、{@code bucketName} 默认 {@code your-bucket-name}。
 * 之前 {@code POST /user/updateImage} 会照样把请求推进 {@code OSSClientBuilder.build(...)}
 * 再 {@code putObject}，最终抛一个 SDK 层的 {@code ClientException}——被兜底记成 500
 * 「服务器内部错误」，看不出真正的原因是这台机器根本没配凭据。
 * 这里只测判定与拒绝，绝不发网络请求。
 */
@DisplayName("OSS 未配置时的拒绝")
class OssUtilConfigGuardTest {

    private static OssUtil configured() {
        OssUtil u = new OssUtil();
        u.setEndpoint("oss-cn-hangzhou.aliyuncs.com");
        u.setAccessKeyId("LTAI-real-looking-id");
        u.setAccessKeySecret("real-looking-secret");
        u.setBucketName("amz-erp");
        u.setAccessUrl("https://amz-erp.oss-cn-hangzhou.aliyuncs.com");
        return u;
    }

    @Test
    @DisplayName("占位符/空值一律判为未配置，上传与删除都拒绝")
    void placeholdersRefuseBothOperations() {
        OssUtil u = configured();
        u.setAccessKeyId("your-access-key-id");
        assertFalse(u.isConfigured(), "your-* 占位符必须算未配置");
        CodeErrorException up = assertThrows(CodeErrorException.class, () -> u.uploadImg(new byte[]{1}));
        assertTrue(up.getMessage().contains("未配置"), up.getMessage());
        assertThrows(CodeErrorException.class, () -> u.deleteImg("https://x/y.png"));
    }

    @Test
    @DisplayName("逐项都要看：只把 accessKeySecret 置空也算未配置")
    void blankSecretIsNotConfigured() {
        OssUtil u = configured();
        u.setAccessKeySecret("   ");
        assertFalse(u.isConfigured(), "空白密钥不能当成已配置");
        assertThrows(CodeErrorException.class, () -> u.uploadImg(new byte[]{1}));
    }

    @Test
    @DisplayName("配置齐全时判定为可用（不去建连接、不发请求）")
    void fullConfigIsAccepted() {
        assertTrue(configured().isConfigured());
    }

    @Test
    @DisplayName("accessUrl 缺失只挡上传：返回一个打不开的 URL 比拒绝更坏")
    void missingAccessUrlRefusesUploadOnly() {
        OssUtil u = configured();
        u.setAccessUrl(null);
        assertTrue(u.isConfigured(), "isConfigured 只管前四项，删图不需要访问域名");
        CodeErrorException ex = assertThrows(CodeErrorException.class, () -> u.uploadImg(new byte[]{1}));
        assertTrue(ex.getMessage().contains("accessUrl"), ex.getMessage());
    }
}
