-- Flyway Migration V4: 商品/消息表唯一键补上 shop_id（跨店覆盖缺陷）
-- Service: amz-service-multiplatform
--
-- Why
--   V1 建表时把唯一键建成 (platform, platform_product_id) 和
--   (platform, platform_message_id)，都不含 shop_id。服务层 MultiplatformServiceImpl
--   的 syncProducts / syncMessages exist 查询也只按 (platform, platform_x_id) 匹配，
--   于是同一平台下不同店铺拿到同一个平台侧 ID 时：
--     * 商品：命中别的店的行后 setId(exist.getId()) + updateById，把那行整条改写成
--       自己店铺的 —— 原店铺商品从自己列表消失，它人工建的 ASIN/SKU 映射一并易主；
--     * 消息：命中别的店的行后什么都不做，本店这条消息永远不落库且无任何信号。
--   这不是越权（入口有 @ShopScoped + @RequireRole），是租户边界漏在查询与 DDL 上。
--
-- 存量数据说明（重要，不要按直觉去重）
--   唯一键目前全局唯一，所以同一 (platform, platform_product_id) 只有一行，
--   其 shop_id 是「最后同步的那家店」——也就是说数据早就在互相覆盖了。
--   因此本库不可能存在同键多行，不需要 ad V2 那种先合并再删的去重步骤。
--   但那些被改写过 shop_id 的行，其真实归属已经无法从现有数据推断
--   （既没有同步日志，也没有平台侧原始归属），本迁移不做猜测、不修复内容：
--   只把约束改对，让后续同步不再互相覆盖。
--
-- 回滚：见 docs/superpowers/runbooks/（本迁移的逆操作是删新键加回旧键；
--   但旧键正是缺陷本身，回滚等于恢复跨店覆盖，不建议执行）。
--
-- 与代码变更的关系：代码侧（MultiplatformServiceImpl）已同步给 exist 查询补上 shop_id。
--   DDL 先上、代码后上：中间窗口内 exist 查询仍查不到本店行，会走到 insert，
--   同键行撞新唯一键而报错（失败可见，不会静默覆盖）。
--   代码先上、DDL 后上：exist 查询带 shop_id 后查不到跨店行，改走 insert，
--   旧唯一键 (platform, platform_product_id) 会拦住并报错（同样失败可见）。
--   即两个顺序都是 fail-fast，不可能出现「上线半套然后静默错」——
--   但都会让同步接口报错，所以仍应作为同一批发布，缩短故障窗口。

-- 商品映射表：唯一键改为含 shop_id
ALTER TABLE amz_platform_product
    DROP INDEX uk_platform_product,
    ADD UNIQUE KEY uk_shop_platform_product (shop_id, platform, platform_product_id);

-- 消息表：唯一键改为含 shop_id
ALTER TABLE amz_platform_message
    DROP INDEX uk_platform_msg_id,
    ADD UNIQUE KEY uk_shop_platform_msg (shop_id, platform, platform_message_id);
