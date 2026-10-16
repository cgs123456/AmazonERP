-- Flyway Migration V6: 店铺级角色默认值 OPERATOR → VIEWER
-- Service: amz-service-user
--
-- Why（HANDOFF §S22 店铺级角色接通）：
--   V1 建 amz_user_shop 时 role 默认 'OPERATOR'，比 amz_user.role 的默认 'VIEWER' 更宽。
--   在此之前这不构成问题——那张表的 role 只写不读（能配、能显示、判定时从不生效），
--   默认值再宽也没人消费。
--
--   本版起 @RequireRole / ShopIdGuardAspect / getInfo 真正按店铺角色判定，
--   于是「插入绑定时不显式给 role」这种行为第一次有了安全后果：拿到 OPERATOR，
--   而该用户的全局角色可能只是 VIEWER。默认值必须与全局默认对齐到最小权限。
--
-- 为什么不改存量行：
--   存量绑定行的 OPERATOR 可能是管理员有意配置的（本版之前它就是靠显式赋值写入的，
--   见 ShopServiceImpl.assignUser 的角色校验），批量改写等于替管理员做授权决策。
--   这里只改「以后新插入的行」，存量行保持原样。
--
-- 兼容性：只改列默认值，不改类型、不动数据；MODIFY COLUMN 在 MySQL 8.0 上是
--   原位变更（VARCHAR(20) 长度与排序规则均不变），不重建表。
-- 回滚：ALTER TABLE amz_user_shop MODIFY COLUMN role VARCHAR(20) DEFAULT 'OPERATOR'
--   COMMENT 'ADMIN/OPERATOR/VIEWER';

ALTER TABLE amz_user_shop
    MODIFY COLUMN role VARCHAR(20) DEFAULT 'VIEWER' COMMENT 'ADMIN/OPERATOR/VIEWER';
