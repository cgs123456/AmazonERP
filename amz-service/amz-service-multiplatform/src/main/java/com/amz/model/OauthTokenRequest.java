package com.amz.model;

import lombok.Data;

import java.io.Serializable;

/**
 * OAuth Token 签发请求体（机机换发接口的唯一传输位）。
 * <p>
 * 密钥只允许随 JSON 请求体传输：query 串会进 nginx-ingress 等基础设施的访问日志
 * （网关自身只记 path，但 ingress 层默认记完整 request line）。
 * 本端点不在网关白名单内，调用方必须持 JWT，故不存在需要 query 兼容期的外部 ISV。
 */
@Data
public class OauthTokenRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    private String appKey;
    private String appSecret;
    private String[] scopes;
    private Long shopId;
}
