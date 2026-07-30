-- ============================================
-- Amazon ERP 数据库初始化脚本
-- 仅负责建库（CREATE DATABASE）与 USE 语句
-- 所有建表语句由后续 02-17 模块脚本负责，避免同名表 schema 冲突
-- ============================================

-- 用户数据库（用户表、关注表由 02 创建；店铺 RBAC 表由 07 创建）
CREATE DATABASE IF NOT EXISTS amz_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_user;

-- 商品数据库（购物车/优惠券等辅助表由 04 创建；商品主数据由 09 创建）
CREATE DATABASE IF NOT EXISTS amz_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_product;

-- 订单数据库（订单属性表由 05 创建；Amazon 订单主表由 08 创建；利润核算由 09 创建）
CREATE DATABASE IF NOT EXISTS amz_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_order;

-- 搜索数据库（搜索历史由 06 创建）
CREATE DATABASE IF NOT EXISTS amz_search DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_search;

-- SP-API 同步数据库（FBA 库存、补货引擎由 09 创建）
CREATE DATABASE IF NOT EXISTS amz_spapi DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_spapi;