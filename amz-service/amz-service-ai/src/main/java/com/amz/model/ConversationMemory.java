package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话记忆实体。
 * <p>
 * 保存最近 N 轮对话上下文，供 Agent 多轮对话时引用历史信息。
 * 一个 sessionId 对应一次会话，按时间顺序存储。
 */
@Data
@TableName("amz_conversation_memory")
public class ConversationMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID（一次连续对话一个 sessionId） */
    private String sessionId;

    /** 用户 ID */
    private Long userId;

    /** 角色：user / assistant / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 创建时间 */
    private LocalDateTime createTime;
}
