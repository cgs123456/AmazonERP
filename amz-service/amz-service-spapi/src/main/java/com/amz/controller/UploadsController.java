package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.AmazonUploadsRealClient;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Amazon Uploads API 对外接口。
 *
 * <p>仅暴露服务端上传闭环：服务端计算 MD5、调用官方
 * {@code createUploadDestinationForResource}，再把原始字节 PUT 到预签名 URL，
 * 最终只向调用方返回 {@code uploadDestinationId}。预签名写 URL 不进入 HTTP 响应，
 * 避免浏览器、网关访问日志或前端错误上报泄露写权限。
 *
 * <p>写操作要求 {@code OPERATOR/ADMIN}，并带 {@link ShopScoped} 店铺隔离。
 * {@code mock} profile 下不注册本控制器：无真实凭据时不得伪造上传成功。
 */
@RestController
@RequestMapping("/spapi/uploads")
@Profile("!mock")
public class UploadsController {

    private static final Logger log = LoggerFactory.getLogger(UploadsController.class);

    @Autowired
    private AmazonUploadsRealClient client;

    @Value("${spring.servlet.multipart.max-file-size:10MB}")
    private DataSize maxFileSize = DataSize.ofMegabytes(10);

    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> upload(@RequestParam Long shopId,
                                 @RequestParam String marketplaceId,
                                 @RequestParam String resource,
                                 @RequestParam(required = false) String contentType,
                                 @RequestPart("file") MultipartFile file) {
        if (marketplaceId == null || marketplaceId.isBlank()) {
            return Result.failure("marketplaceId must not be blank",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (resource == null || resource.isBlank()) {
            return Result.failure("resource must not be blank",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (file == null || file.isEmpty()) {
            return Result.failure("file must not be empty",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (file.getSize() > maxFileSize.toBytes()) {
            return Result.failure("file exceeds max size: " + maxFileSize,
                    ErrorSummary.localError(LocalApiException.CODE_FILE_TOO_LARGE));
        }

        String effectiveContentType = contentType == null || contentType.isBlank()
                ? file.getContentType()
                : contentType;
        try {
            String destinationId = client.createUploadDestinationAndUpload(
                    shopId, marketplaceId, resource, effectiveContentType, file.getBytes());
            return Result.success(destinationId);
        } catch (Exception e) {
            log.error("Uploads attachment failed shopId={} resource={}", shopId, resource, e);
            return Result.failure("upload failed: " + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }
}
