-- ============================================
-- Amazon ERP Agent 记忆化模块建表脚本
-- 数据库: amz_ai
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_ai;

-- 用户偏好表（Agent 记忆化核心）
CREATE TABLE IF NOT EXISTS amz_user_preference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE COMMENT '用户 ID',
    nickname VARCHAR(64) DEFAULT NULL COMMENT '用户昵称',
    preferred_shop_id BIGINT DEFAULT 1 COMMENT '偏好店铺 ID（默认）',
    preferred_shop_name VARCHAR(100) DEFAULT NULL COMMENT '偏好店铺名称',
    preferred_category VARCHAR(50) DEFAULT NULL COMMENT '关注品类',
    language VARCHAR(5) DEFAULT 'ZH' COMMENT '语言偏好：ZH/EN/JA/DE',
    last_active_time DATETIME DEFAULT NULL COMMENT '上次活跃时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_last_active (last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好表（Agent 记忆化）';

-- 对话记忆表
CREATE TABLE IF NOT EXISTS amz_conversation_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(32) NOT NULL COMMENT '会话 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    role VARCHAR(15) NOT NULL COMMENT '角色：user/assistant/tool',
    content TEXT NOT NULL COMMENT '消息内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_user (user_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话记忆表';
