-- ============================================
-- Amazon ERP 数据库初始化脚本
-- ============================================

-- 用户数据库
CREATE DATABASE IF NOT EXISTS amz_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_user;

CREATE TABLE IF NOT EXISTS amz_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) DEFAULT '',
    image VARCHAR(500) DEFAULT '',
    mail VARCHAR(100) DEFAULT '',
    phone VARCHAR(20) DEFAULT '',
    sex TINYINT DEFAULT 0,
    birthday VARCHAR(20) DEFAULT '',
    address VARCHAR(200) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_attention (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    attention_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_attention (user_id, attention_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入测试用户（密码: 123456 的 BCrypt 加密）
INSERT IGNORE INTO amz_user (id, username, password, nickname, image) VALUES
(1, 'testuser', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '测试卖家', 'https://i.pravatar.cc/150?img=1'),
(2, 'amazonseller', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'Amazon 官方卖家', 'https://i.pravatar.cc/150?img=2');

-- 商品数据库
CREATE DATABASE IF NOT EXISTS amz_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_product;

CREATE TABLE IF NOT EXISTS amz_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price DECIMAL(10,2) NOT NULL DEFAULT 0,
    original_price DECIMAL(10,2) DEFAULT 0,
    description TEXT,
    image VARCHAR(500) DEFAULT '',
    images TEXT,
    stock INT DEFAULT 0,
    sales INT DEFAULT 0,
    shop_id BIGINT DEFAULT 0,
    custom_attribute TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_shop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500) DEFAULT '',
    description TEXT,
    image VARCHAR(500) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_cart (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    count INT DEFAULT 1,
    custom_attribute TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_product_browse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    discount DECIMAL(10,2) DEFAULT 0,
    `limit` DECIMAL(10,2) DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_user_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入测试商品
INSERT IGNORE INTO amz_shop (id, name, description) VALUES
(1, 'Amazon 官方店铺', 'Amazon 自营店铺，品质保证');

INSERT IGNORE INTO amz_product (id, name, price, original_price, description, image, stock, sales, shop_id) VALUES
(1, '无线蓝牙耳机', 79.00, 99.00, '降噪蓝牙耳机，长续航', 'https://picsum.photos/300/300?random=101', 100, 50, 1),
(2, '瑜伽垫加厚防滑', 25.00, 35.00, 'TPE 环保材质，运动健身', 'https://picsum.photos/300/300?random=102', 200, 150, 1),
(3, '不锈钢保温杯', 19.00, 29.00, '大容量户外旅行水壶', 'https://picsum.photos/300/300?random=103', 50, 30, 1);

-- 订单数据库
CREATE DATABASE IF NOT EXISTS amz_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_order;

CREATE TABLE IF NOT EXISTS amz_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    status TINYINT DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已发货 3-已完成 4-已取消',
    custom_attribute TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_order_attribute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    name VARCHAR(100) DEFAULT '',
    value VARCHAR(200) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 搜索数据库
CREATE DATABASE IF NOT EXISTS amz_search DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_search;

CREATE TABLE IF NOT EXISTS amz_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
