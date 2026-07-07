-- ============================================
-- 小红书项目数据库初始化脚本
-- ============================================

-- 用户数据库
CREATE DATABASE IF NOT EXISTS rb_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rb_user;

CREATE TABLE IF NOT EXISTS rb_user (
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

CREATE TABLE IF NOT EXISTS rb_attention (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    attention_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_attention (user_id, attention_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入测试用户（密码: 123456 的 BCrypt 加密）
INSERT IGNORE INTO rb_user (id, username, password, nickname, image) VALUES
(1, 'testuser', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '测试用户', 'https://i.pravatar.cc/150?img=1'),
(2, 'xiaohongshu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '小红书官方', 'https://i.pravatar.cc/150?img=2');

-- 笔记数据库
CREATE DATABASE IF NOT EXISTS rb_note DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rb_note;

CREATE TABLE IF NOT EXISTS rb_note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    content TEXT,
    image VARCHAR(500) DEFAULT '',
    `like` INT DEFAULT 0,
    collection INT DEFAULT 0,
    comment INT DEFAULT 0,
    type VARCHAR(50) DEFAULT '',
    time VARCHAR(50) DEFAULT '',
    address VARCHAR(200) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_like (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    note_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_note (user_id, note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_collection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    note_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_note (user_id, note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_note_browse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    note_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_topic (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_note_topic (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    note_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入测试笔记
INSERT IGNORE INTO rb_note (id, user_id, title, content, image, `like`, collection, type) VALUES
(1, 1, '分享我家的装修风格！超喜欢这个飘窗设计', '最近刚装修完，飘窗是我最满意的部分，阳光洒进来超治愈~', 'https://picsum.photos/300/400?random=1', 1700, 200, '家居'),
(2, 2, '三口之家的幸福时光🌸这样的周末太治愈了', '周末带娃去公园，记录下这些美好的瞬间', 'https://picsum.photos/300/500?random=2', 3200, 500, '生活'),
(3, 1, '💍 100万打造的婚礼现场！每个细节都超满意', '从策划到执行花了半年时间，终于圆满了！', 'https://picsum.photos/300/350?random=3', 5100, 800, '婚庆'),
(4, 2, '把白娘子送到法国庄园🏰 这波操作绝了', '旅拍分享，中西合璧的感觉太绝了', 'https://picsum.photos/300/450?random=4', 8900, 1200, '旅行'),
(5, 1, '最爱拉丝奶酪🧀️ 这家店的芝士真的太绝了', '探店分享，这家店的芝士披萨绝了', 'https://picsum.photos/300/380?random=5', 2300, 300, '美食'),
(6, 2, '美妆分享｜让皮肤变好的秘密都在这里了', '坚持用了三个月，皮肤真的变好了', 'https://picsum.photos/300/420?random=6', 1800, 250, '美妆');

-- 商品数据库
CREATE DATABASE IF NOT EXISTS rb_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rb_product;

CREATE TABLE IF NOT EXISTS rb_product (
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

CREATE TABLE IF NOT EXISTS rb_shop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500) DEFAULT '',
    description TEXT,
    image VARCHAR(500) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_cart (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    count INT DEFAULT 1,
    custom_attribute TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_product_browse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    discount DECIMAL(10,2) DEFAULT 0,
    `limit` DECIMAL(10,2) DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_user_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入测试商品
INSERT IGNORE INTO rb_shop (id, name, description) VALUES
(1, '小红书官方旗舰店', '小红书自营店铺，品质保证');

INSERT IGNORE INTO rb_product (id, name, price, original_price, description, image, stock, sales, shop_id) VALUES
(1, '小红书限定口红', 199.00, 299.00, '超好看的限定色号', 'https://picsum.photos/300/300?random=101', 100, 50, 1),
(2, '网红防晒霜SPF50+', 128.00, 168.00, '清爽不油腻，防晒黑科技', 'https://picsum.photos/300/300?random=102', 200, 150, 1),
(3, '手工编织包包', 259.00, 359.00, '纯手工编织，独一无二', 'https://picsum.photos/300/300?random=103', 50, 30, 1);

-- 订单数据库
CREATE DATABASE IF NOT EXISTS rb_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rb_order;

CREATE TABLE IF NOT EXISTS rb_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    status TINYINT DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已发货 3-已完成 4-已取消',
    custom_attribute TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rb_order_attribute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    name VARCHAR(100) DEFAULT '',
    value VARCHAR(200) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 搜索数据库
CREATE DATABASE IF NOT EXISTS rb_search DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rb_search;

CREATE TABLE IF NOT EXISTS rb_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
