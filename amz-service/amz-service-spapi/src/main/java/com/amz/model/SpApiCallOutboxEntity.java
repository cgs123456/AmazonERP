package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SP-API 持久化调用与重放队列实体。
 * <p>
 * 请求体、响应体均以 AES-256-GCM 密文保存；查询串与路径不包含长期密钥，
 * 但对外接口仍只返回脱敏后的元数据，绝不回显密文或明文正文。
 */
@Data
@TableName("amz_spapi_call_outbox")
public class SpApiCallOutboxEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("shop_id")
    private Long shopId;

    @TableField("marketplace_id")
    private String marketplaceId;

    @TableField("operation_id")
    private String operationId;

    @TableField("http_method")
    private String httpMethod;

    @TableField("request_path")
    private String requestPath;

    @TableField("request_query")
    private String requestQuery;

    @TableField("request_body_encrypted")
    private String requestBodyEncrypted;

    @TableField("expected_status")
    private Integer expectedStatus;

    @TableField("expected_statuses")
    private String expectedStatuses;

    @TableField("rate_limit_variant")
    private String rateLimitVariant;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("replay_of_id")
    private Long replayOfId;

    @TableField("token_source")
    private String tokenSource;

    @TableField("restricted_resources_encrypted")
    private String restrictedResourcesEncrypted;

    @TableField("restricted_resource_hash")
    private String restrictedResourceHash;

    @TableField("restricted_resource_count")
    private Integer restrictedResourceCount;

    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("max_attempts")
    private Integer maxAttempts;

    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @TableField("last_error_code")
    private String lastErrorCode;

    @TableField("last_error_message")
    private String lastErrorMessage;

    @TableField("response_status")
    private Integer responseStatus;

    @TableField("response_body_encrypted")
    private String responseBodyEncrypted;

    @TableField("response_request_id")
    private String responseRequestId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;
}
