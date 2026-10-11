-- Flyway Migration V3: 重建操作日志审计表
-- Service: amz-service-user
--
-- Why（2026-10-10 业务齐全度推进 P4.1）：
--   amz_oper_log 建于 V1、在 V2 被当作零引用死表删除。本次为「操作审计落库」重建：
--   @OperLog 注解 + OperLogAspect 此前只写 oper-log.log 文件，无库级留痕，
--   财务凭证/索赔、库存回补、采购审批、店铺凭证、角色权限、发货回传等敏感写操作
--   事后无法追责。表结构沿用 V1 原设计（字段与索引保持一致），新增 trace_id 便于
--   与 SkyWalking 链路对齐，新增 shop_id 便于按店铺检索审计。
--   V1 / V2 文件保持原样（Flyway checksum 不可变）。
--
-- 写入方：amz-common 的 OperLogAspect（异步、跨库写 amz_user.amz_oper_log）。
--   各业务服务的 JdbcTemplate 指向各自业务库，因此审计写入用**带库名限定**的 SQL
--   （同 FieldPermissionServiceImpl 读 amz_user.amz_field_permission 的既有先例），
--   前提是业务库与 amz_user 同实例且账号有跨库写权限（compose/k8s 同为单一 MySQL）。
--   审计是旁路留痕：写失败只记日志计数，**绝不阻断业务**（切面内已 try/catch）。
--
-- 兼容性：MySQL 8.0；CREATE TABLE IF NOT EXISTS 可重复执行。
-- 回滚：DROP TABLE IF EXISTS amz_user.amz_oper_log;（审计属旁路，丢弃不影响业务数据）

CREATE TABLE IF NOT EXISTS amz_oper_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    user_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    shop_id BIGINT DEFAULT NULL COMMENT '操作所属店铺 ID（可空：跨店铺/系统级操作）',
    module VARCHAR(64) NOT NULL COMMENT '业务模块名',
    action VARCHAR(64) NOT NULL COMMENT '操作类型',
    description VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    method VARCHAR(255) NOT NULL COMMENT '执行方法签名',
    params TEXT DEFAULT NULL COMMENT '方法入参（JSON，超长截断）',
    result TEXT DEFAULT NULL COMMENT '返回值（JSON，超长截断）',
    ip VARCHAR(64) DEFAULT NULL COMMENT '请求来源 IP',
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行状态 SUCCESS/FAIL',
    error_msg TEXT DEFAULT NULL COMMENT '异常信息',
    trace_id VARCHAR(64) DEFAULT NULL COMMENT '链路追踪 ID（对齐 SkyWalking，可空）',
    cost_time BIGINT DEFAULT 0 COMMENT '方法耗时（毫秒）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_user_id (user_id),
    INDEX idx_shop_id (shop_id),
    INDEX idx_create_time (create_time),
    INDEX idx_module (module),
    INDEX idx_module_status (module, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志审计表';
