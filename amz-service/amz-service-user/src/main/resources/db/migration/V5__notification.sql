-- Flyway Migration V5: 站内通知历史（消息中心落库）
-- Service: amz-service-user
--
-- Why（P1.6）：
--   站内通知此前只走 WebSocket 推送（message 服务无数据库），用户不在线即丢、
--   刷新即丢、无历史——前端 NotificationPage 自己都声明「服务端不落库」。
--   对 ERP 而言「库存预警/订单异常」这类通知错过了就是事故，必须有历史可查。
--
-- 写入方：message 服务经 Feign 在推送时同步落库（在线/离线都落——
--   离线恰恰是历史最该兜住的场景）；读方：本服务的 /user/notification/*。
--   放在 amz_user 库而不是给 message 单开库：通知按用户归属，且避免新增
--   第 15 个业务库触发全套建库契约。
--
-- 兼容性：仅新增表。
-- 回滚：DROP TABLE amz_notification;

CREATE TABLE IF NOT EXISTS amz_notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '接收用户 ID',
    type INT DEFAULT 0 COMMENT '消息类型（MessageTypeEnum 序号：0=库存预警/1=订单异常/2=补货建议/3=差评/4=价格异动）',
    title VARCHAR(100) DEFAULT NULL COMMENT '标题',
    content VARCHAR(1000) DEFAULT NULL COMMENT '内容',
    is_read TINYINT NOT NULL DEFAULT 0 COMMENT '是否已读：0=未读 1=已读',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, create_time),
    INDEX idx_user_unread (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知历史';
