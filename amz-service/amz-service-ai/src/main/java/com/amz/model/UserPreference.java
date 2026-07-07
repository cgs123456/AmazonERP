package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户偏好实体（Agent 记忆化核心数据）。
 * <p>
 * Agent 在对话中自动提取/更新用户偏好，用于：
 * <ol>
 *   <li>用户未指定 shopId 时使用偏好店铺</li>
 *   <li>用户未指定品类时聚焦关注品类</li>
 *   <li>按语言偏好返回回复</li>
 *   <li>主动提醒：库存异动、销量下滑等</li>
 * </ol>
 */
@Data
@TableName("amz_user_preference")
public class UserPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 用户昵称 */
    private String nickname;

    /** 偏好店铺 ID（默认店铺） */
    private Long preferredShopId;

    /** 偏好店铺名称（便于展示） */
    private String preferredShopName;

    /** 关注品类（如 Electronics / Home / Beauty） */
    private String preferredCategory;

    /** 语言偏好：ZH/EN/JA/DE */
    private String language;

    /** 上次活跃时间 */
    private LocalDateTime lastActiveTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
