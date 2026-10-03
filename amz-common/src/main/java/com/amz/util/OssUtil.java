package com.amz.util;

import com.amz.exception.CodeErrorException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import lombok.Data;
import java.io.ByteArrayInputStream;
import java.util.UUID;

/**
 * 阿里云oss工具类
 */
@Data
public class OssUtil {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;
    /** OSS 公网访问域名（用于拼装上传后返回的 URL） */
    private String accessUrl;

    /**
     * 上传图片
     * @param bytes
     * @return
     */
    /**
     * 配置是否真的可用：yml 里这几项的默认值是 your-access-key-id / 空串 / your-bucket-name，
     * 拿着占位符去调 SDK 只会回一个看不懂的 ClientException/403，运维分不清是「凭据错」还是「没配」。
     */
    public boolean isConfigured() {
        return usable(endpoint) && usable(accessKeyId) && usable(accessKeySecret)
                && usable(bucketName);
    }

    private static boolean usable(String v) {
        return v != null && !v.trim().isEmpty() && !v.trim().startsWith("your-");
    }

    private void requireConfigured(String action, boolean needAccessUrl) {
        if (!isConfigured()) {
            throw new CodeErrorException("对象存储未配置：oss.endpoint/accessKeyId/accessKeySecret/bucketName 仍有空值或 your-* 占位符，" + action + "已拒绝执行（这不是上传失败，配好凭据后才能用）");
        }
        if (needAccessUrl && !usable(accessUrl)) {
            throw new CodeErrorException("对象存储未配置：oss.accessUrl 为空，上传后无法拼出可访问地址， 与其返回一个打不开的 URL，不如直接拒绝");
        }
    }


    public String uploadImg(byte[] bytes){
        requireConfigured("上传图片", true);
        String fileName = UUID.randomUUID().toString().concat(".png");
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    bucketName, fileName, new ByteArrayInputStream(bytes));
            ossClient.putObject(putObjectRequest);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
        // accessUrl 由配置注入，默认 https://amz-erp.oss-cn-hangzhou.aliyuncs.com
        return accessUrl + "/" + fileName;
    }

    /**
     * 删除图片
     * @param imageUrl 图片URL
     */
    public void deleteImg(String imageUrl) {
        // 守卫必须在 try 之外：下面有 catch (Exception e) -> RuntimeException，
        // 放里面会把「未配置」这条业务拒绝包装成一个不透明的删除失败。
        requireConfigured("删除图片", false);
        OSS ossClient = null;
        try {
            String fileName = imageUrl.substring(imageUrl.lastIndexOf("/") + 1);
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            ossClient.deleteObject(bucketName, fileName);
        } catch (Exception e) {
            throw new RuntimeException("删除OSS图片失败: " + imageUrl, e);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }
}
