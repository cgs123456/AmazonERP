-- Flyway Migration V4: 用户账号状态（启用/禁用）
-- Service: amz-service-user
--
-- Why（角色/用户管理 P1.4）：
--   amz_user 此前只有 role 列（V1），没有账号状态——无法禁用一个离职/异常账号，
--   只能等 JWT 自然过期（默认 24h）。禁用是账号管理的最小闭环。
--
-- 语义：status 1=正常（默认，存量行不受影响） 0=禁用。
--   禁用后的行为：登录（verify）立即拒绝。已签发的 JWT 在过期前（默认 24h）仍然有效
--   ——这与「鉴权路径不查库」的既有架构取舍一致（BaseAuthInterceptor 只解析 token，
--   运行时不查库，见 S7.2 店铺体系同款限制）；需要立即踢出的场景属后续增强，
--   届时应引入短 TTL 状态缓存而不是每个请求查库。
--
-- 兼容性：ADD COLUMN 带默认 1，存量行自动视为正常。
-- 回滚：ALTER TABLE amz_user DROP COLUMN status;

ALTER TABLE amz_user
    ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态：1=正常 0=禁用';
