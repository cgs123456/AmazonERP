-- Flyway Migration V2: 清理零引用死表
-- Service: amz-service-user
--
-- Why（2026-10-05 全方位 review + 用户拍板「死表直接清理」）：
--   amz_attention / amz_oper_log 建于 V1，全仓（Java/XML/工具链）无任何读写，
--   zero_reference_tables.py 长期列为零引用。产品已确认不接线，直接删除。
--   V1 文件保持原样（Flyway checksum 不可变），历史 DDL 见 V1。
--
-- 兼容性：表内无代码数据依赖；synthetic-data 管线（LAYOUTS）已同步摘除。
DROP TABLE IF EXISTS amz_attention;
DROP TABLE IF EXISTS amz_oper_log;
