# DDL 快照与漂移报告（自动生成，请勿手工编辑）

生成器：`tools/synthetic-data/snapshot_schema.py`（工具版本 1.1.0）

本报告由仓库内 DDL 解析得出，是模拟数据生成器的**唯一列清单来源**（spec §7.9 要求 1：禁止手抄列定义）。

## 1. 来源与表数

| 来源分组 | 文件数 | CREATE TABLE 数 |
|---|---|---|
| `aux-ddl` | 1 | 1 |
| `compose-init-sql` | 32 | 105 |
| `flyway-migration` | 22 | 106 |
| `root-stale-dump` | 1 | 58 |
| **去重并集** | 56 个文件 | **107** 张表 |

数据库（14 个）：`amz_ad`、`amz_ai`、`amz_customer`、`amz_finance`、`amz_logistics`、`amz_multiplatform`、`amz_ops`、`amz_order`、`amz_procurement`、`amz_product`、`amz_report`、`amz_search`、`amz_spapi`、`amz_user`

## 2. 同一张表的多份定义

共 106 张表出现多份定义，其中 **6 张存在列级差异**。

| 表 | 定义来源 | 列差异（仅第一份 vs 其它） |
|---|---|---|
| `amz_ad.amz_ad_daily_report` | `docker/init-sql/24-init-tables-ad-upgrade.sql`<br>`amz-service/amz-service-ad/src/main/resources/db/migration/V1__init.sql`<br>`init_all_tables.sql` | `docker/init-sql/24-init-tables-ad-upgrade.sql` → `init_all_tables.sql`：类型/默认值不同：campaign_id |
| `amz_ad.amz_ad_keyword` | `docker/init-sql/10-init-tables-ad.sql`<br>`amz-service/amz-service-ad/src/main/resources/db/migration/V1__init.sql`<br>`init_all_tables.sql` | `docker/init-sql/10-init-tables-ad.sql` → `init_all_tables.sql`：仅 B 有：base_bid |
| `amz_logistics.amz_shipment` | `docker/init-sql/13-init-tables-logistics.sql`<br>`amz-service/amz-service-logistics/src/main/resources/db/migration/V1__init.sql`<br>`init_all_tables.sql` | `docker/init-sql/13-init-tables-logistics.sql` → `amz-service/amz-service-logistics/src/main/resources/db/migration/V1__init.sql`：仅 A 有：data_source,last_track_time |
| `amz_logistics.amz_tracking_event` | `docker/init-sql/13-init-tables-logistics.sql`<br>`amz-service/amz-service-logistics/src/main/resources/db/migration/V1__init.sql`<br>`init_all_tables.sql` | `docker/init-sql/13-init-tables-logistics.sql` → `amz-service/amz-service-logistics/src/main/resources/db/migration/V1__init.sql`：仅 A 有：raw_status,source |
| `amz_multiplatform.amz_unified_order` | `docker/init-sql/16-init-tables-multiplatform.sql`<br>`amz-service/amz-service-multiplatform/src/main/resources/db/migration/V1__init.sql`<br>`init_all_tables.sql` | `docker/init-sql/16-init-tables-multiplatform.sql` → `amz-service/amz-service-multiplatform/src/main/resources/db/migration/V1__init.sql`：仅 A 有：items_json |
| `amz_user.amz_user` | `docker/init-sql/02-init-tables-user.sql`<br>`amz-service/amz-service-user/src/main/resources/db/migration/V1__init.sql`<br>`init_all_tables.sql` | `docker/init-sql/02-init-tables-user.sql` → `init_all_tables.sql`：仅 B 有：role |

## 3. 结构风险清单

- 无主键表：**0** 张
- 显式外键：**0** 张表（其余表的关联仅存在于应用层，模拟数据生成器必须自行保证顺序）
- 视图：1 个（`v_profit_summary_by_sku`）
- 阻断性解析问题：**0** 条
- 守卫式动态 DDL：15 条（不参与静态表清单展开）
- 列定义不一致（同一列在不同定义中类型/默认值不同）：**1** 列
- 存在`条件列`（并非每份定义都有）的表：**5** 张 / 共 7 列 -> 生成器默认**不写入**这些列，只有 `--include-conditional` 才写入（对应升级脚本执行成功的库）

| 表 | 条件列（缺失于部分定义） |
|---|---|
| `amz_ad.amz_ad_keyword` | `base_bid` |
| `amz_logistics.amz_shipment` | `data_source`、`last_track_time` |
| `amz_logistics.amz_tracking_event` | `source`、`raw_status` |
| `amz_multiplatform.amz_unified_order` | `items_json` |
| `amz_user.amz_user` | `role` |

## 4. ALTER TABLE 事实（MySQL 8 兼容性，P0-39 机器可核证据）

- ALTER TABLE 语句：**5** 条；其中 ADD COLUMN：**15** 条
- 使用 `ADD COLUMN IF NOT EXISTS` 的语句：**4** 条（MySQL 8 不支持该语法，执行即 `ERROR 1064`）

| 文件 | 行 | 表 | 语句 |
|---|---|---|---|
| `docker/init-sql/19-init-tables-field-permission.sql` | 28 | `amz_user` | `ALTER TABLE amz_user ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'VIEWER' COMMENT '角色 ADMIN/OPERATOR/VIEW` |
| `docker/init-sql/23-init-tables-procurement-upgrade.sql` | 117 | `amz_purchase_order` | `ALTER TABLE amz_purchase_order ADD COLUMN IF NOT EXISTS supplier_id BIGINT DEFAULT NULL COMMENT '供应商 ID' AFTER supplier_` |
| `amz-service/amz-service-procurement/src/main/resources/db/migration/V1__init.sql` | 141 | `amz_purchase_order` | `ALTER TABLE amz_purchase_order ADD COLUMN IF NOT EXISTS supplier_id BIGINT DEFAULT NULL COMMENT '供应商 ID', ADD COLUMN IF ` |
| `amz-service/amz-service-user/src/main/resources/db/migration/V1__init.sql` | 94 | `amz_user` | `ALTER TABLE amz_user ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'VIEWER' COMMENT '角色 ADMIN/OPERATOR/VIEW` |

