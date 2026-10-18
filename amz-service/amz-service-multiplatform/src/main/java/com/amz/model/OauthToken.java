package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * OAuth token 记录。<b>当前只写不读：这里的 {@code expiresAt} 不会被任何代码检查。</b>
 *
 * <p>只有 {@code MultiplatformServiceImpl#generateToken} 会插入本表，全仓没有
 * 任何查询它的代码——没有 token 校验端点、没有鉴权过滤器、没有过期判定、
 * 没有吊销、没有刷新。所以 {@code expiresAt} 与 {@code refreshToken} 目前
 * 只是写着好看的字段：token 到期不会导致任何东西被拒绝，因为它压根不被采纳。
 *
 * <p>补全开放 API 鉴权时应以本表为基准（§S33.94），在那之前
 * 不要因为看到这些字段就认为系统「支持 token 过期/刷新」。
 */
@Data
@TableName("amz_oauth_token")
public class OauthToken implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long appId;
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private LocalDateTime expiresAt;
    private String scopes;
    private Long shopId;
    private LocalDateTime createTime;
}
