package com.amz.util;

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

    /**
     * 上传图片
     * @param bytes
     * @return
     */
    public String uploadImg(byte[] bytes){
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
        // https://amz-erp.oss-cn-hangzhou.aliyuncs.com/default.png
        return "https://amz-erp.oss-cn-hangzhou.aliyuncs.com/" + fileName;
    }

    /**
     * 删除图片
     * @param imageUrl 图片URL
     */
    public void deleteImg(String imageUrl) {
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
