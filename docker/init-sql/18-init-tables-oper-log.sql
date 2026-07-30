-- ============================================
-- Amazon ERP 操作日志审计建表脚本
-- 数据库: amz_user（操作日志与用户体系共库，便于按 user_id 关联审计）
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_user;

-- 操作日志审计表
CREATE TABLE IF NOT EXISTS amz_oper_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    user_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID（来自 UserContext，未登录时为空）',
    module VARCHAR(64) NOT NULL COMMENT '业务模块名',
    action VARCHAR(64) NOT NULL COMMENT '操作类型，如 查询/新增/修改/删除/导出',
    description VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    method VARCHAR(255) NOT NULL COMMENT '执行方法签名',
    params TEXT DEFAULT NULL COMMENT '方法入参（JSON）',
    result TEXT DEFAULT NULL COMMENT '返回值（JSON，异常时为空）',
    ip VARCHAR(64) DEFAULT NULL COMMENT '请求来源 IP',
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行状态：SUCCESS / FAIL',
    error_msg TEXT DEFAULT NULL COMMENT '异常信息（status=FAIL 时填充）',
    cost_time BIGINT DEFAULT 0 COMMENT '方法耗时（毫秒）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time),
    INDEX idx_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志审计表';
